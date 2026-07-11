package com.yanban.paper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexMaskingService;
import com.yanban.paper.latex.LatexParserService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperCitationClosureServiceTest {

    private static final String FIRST_ORIGINAL =
            "Prior work studies broad polarimetric target estimation in difficult radar environments.";
    private static final String FIRST_REPLACEMENT =
            "Prior work studies polarimetric target estimation in radar environments.";
    private static final String SECOND_ORIGINAL =
            "Existing studies also examine broad waveform detection under several conditions.";
    private static final String SECOND_REPLACEMENT =
            "Existing studies also examine waveform detection in radar systems.";

    @Test
    void noFitAffectsOnlyItsOwnSuggestionInsideBatch() {
        Harness harness = harness(criticAndNoFitModel());

        PaperCitationClosureService.ClosureResult result = harness.service().close(1L, document());

        assertThat(result.eligibleCount()).isEqualTo(2);
        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.reportOnlyCount()).isEqualTo(1);
        assertThat(result.outcomes()).extracting(PaperCitationClosureService.ClosureOutcome::suggestionId)
                .containsExactly(1L, 2L);
        assertThat(result.outcomes().get(0).accepted()).isTrue();
        assertThat(result.outcomes().get(1).accepted()).isFalse();
        assertThat(result.outcomes().get(1).reason()).contains("does not support");
        verifyPersisted(harness.persistence(), result);
    }

    @Test
    void malformedVerifierItemRetriesOnlyThatSuggestion() {
        Harness harness = harness(perSuggestionRetryModel());

        PaperCitationClosureService.ClosureResult result = harness.service().close(1L, document());

        assertThat(result.acceptedCount()).isEqualTo(2);
        Map<Long, Integer> attempts = Map.of(
                result.outcomes().get(0).suggestionId(), result.outcomes().get(0).attempts(),
                result.outcomes().get(1).suggestionId(), result.outcomes().get(1).attempts());
        assertThat(attempts).containsEntry(1L, 1).containsEntry(2L, 2);
        verifyPersisted(harness.persistence(), result);
    }

    @Test
    void unresolvedSuggestionsStopAfterThreeBatchRounds() {
        AtomicInteger criticCalls = new AtomicInteger();
        PaperModelClient invalidModel = (system, prompt, temperature, maxTokens) -> {
            assertThat(prompt).startsWith("# Full-Introduction Citation Closure Critic");
            criticCalls.incrementAndGet();
            return "{}";
        };
        Harness harness = harness(invalidModel);

        PaperCitationClosureService.ClosureResult result = harness.service().close(1L, document());

        assertThat(result.acceptedCount()).isZero();
        assertThat(result.reportOnlyCount()).isEqualTo(2);
        assertThat(result.outcomes()).allSatisfy(outcome -> assertThat(outcome.attempts()).isEqualTo(3));
        assertThat(criticCalls).hasValue(3);
        verifyPersisted(harness.persistence(), result);
    }

    private Harness harness(PaperModelClient modelClient) {
        SuggestionRepository suggestions = mock(SuggestionRepository.class);
        SuggestionEvidenceRepository evidence = mock(SuggestionEvidenceRepository.class);
        LiteratureCardRepository cards = mock(LiteratureCardRepository.class);
        PaperCitationClosurePersistenceService persistence = mock(PaperCitationClosurePersistenceService.class);
        Suggestion first = suggestion(1L);
        Suggestion second = suggestion(2L);
        LiteratureCard firstCard = card(11L, "Target estimation evidence");
        LiteratureCard secondCard = card(12L, "Waveform detection evidence");
        when(suggestions.findByTaskIdOrderByCreatedAt(1L)).thenReturn(List.of(first, second));
        when(evidence.findBySuggestionIdIn(List.of(1L, 2L))).thenReturn(List.of(
                new SuggestionEvidence(1L, 11L),
                new SuggestionEvidence(2L, 12L)));
        when(cards.findAllById(any())).thenReturn(List.of(firstCard, secondCard));

        PaperCitationClosureService service = new PaperCitationClosureService(
                suggestions,
                evidence,
                cards,
                new PaperPromptService(),
                modelClient,
                new LatexMaskingService(),
                new ObjectMapper(),
                persistence);
        return new Harness(service, persistence);
    }

    private PaperModelClient criticAndNoFitModel() {
        return (system, prompt, temperature, maxTokens) -> {
            if (prompt.startsWith("# Full-Introduction Citation Closure Critic")) {
                return """
                        {"diagnoses":[
                          {"suggestionId":1,"action":"NARROW","supportedEvidenceCardIds":[11],"supportedFact":"Polarimetric target estimation is studied.","unsupportedQualifiers":["broad"],"placementGuidance":"Narrow the first sentence.","reason":"Only the narrower claim is supported."},
                          {"suggestionId":2,"action":"NO_FIT","supportedEvidenceCardIds":[],"supportedFact":"","unsupportedQualifiers":[],"placementGuidance":"","reason":"The supplied paper does not support a safe Introduction claim."}
                        ]}
                        """;
            }
            if (prompt.startsWith("# Introduction Citation Orator")) return oratorJson(1L, FIRST_ORIGINAL, FIRST_REPLACEMENT);
            if (prompt.startsWith("# Citation Closure Verification")) return verifierJson(1L, 11L);
            throw new AssertionError("Unexpected prompt: " + prompt);
        };
    }

    private PaperModelClient perSuggestionRetryModel() {
        return (system, prompt, temperature, maxTokens) -> {
            boolean roundTwo = prompt.contains("Repair round: 2");
            if (prompt.startsWith("# Full-Introduction Citation Closure Critic")) {
                if (roundTwo) {
                    assertThat(prompt).contains("\"expectedSuggestionIds\":[2]")
                            .doesNotContain("\"expectedSuggestionIds\":[1,2]");
                    return diagnosisJson(2L, 12L, "waveform detection");
                }
                return "{\"diagnoses\":["
                        + diagnosisItem(1L, 11L, "target estimation") + ","
                        + diagnosisItem(2L, 12L, "waveform detection") + "]}";
            }
            if (prompt.startsWith("# Introduction Citation Orator")) {
                if (roundTwo) return oratorJson(2L, SECOND_ORIGINAL, SECOND_REPLACEMENT);
                return "{\"patches\":["
                        + oratorItem(1L, FIRST_ORIGINAL, FIRST_REPLACEMENT) + ","
                        + oratorItem(2L, SECOND_ORIGINAL, SECOND_REPLACEMENT) + "]}";
            }
            if (prompt.startsWith("# Citation Closure Verification")) {
                if (roundTwo) return verifierJson(2L, 12L);
                return verifierJson(1L, 11L);
            }
            throw new AssertionError("Unexpected prompt: " + prompt);
        };
    }

    private String diagnosisJson(Long suggestionId, Long cardId, String fact) {
        return "{\"diagnoses\":[" + diagnosisItem(suggestionId, cardId, fact) + "]}";
    }

    private String diagnosisItem(Long suggestionId, Long cardId, String fact) {
        return "{\"suggestionId\":" + suggestionId
                + ",\"action\":\"NARROW\",\"supportedEvidenceCardIds\":[" + cardId + "]"
                + ",\"supportedFact\":\"" + fact + " is studied.\",\"unsupportedQualifiers\":[\"broad\"]"
                + ",\"placementGuidance\":\"Use the matching sentence.\",\"reason\":\"Narrow the claim.\"}";
    }

    private String oratorJson(Long suggestionId, String original, String replacement) {
        return "{\"patches\":[" + oratorItem(suggestionId, original, replacement) + "]}";
    }

    private String oratorItem(Long suggestionId, String original, String replacement) {
        return "{\"suggestionId\":" + suggestionId
                + ",\"decision\":\"APPLY\",\"operation\":\"NARROW\""
                + ",\"originalAnchor\":\"" + original + "\""
                + ",\"replacementText\":\"" + replacement + "\""
                + ",\"citationAnchor\":\"" + replacement + "\""
                + ",\"reason\":\"The narrower claim fits the paragraph.\"}";
    }

    private String verifierJson(Long suggestionId, Long cardId) {
        return "{\"verifications\":[{\"suggestionId\":" + suggestionId
                + ",\"verdict\":\"SUPPORTED\",\"acceptedEvidenceCardIds\":[" + cardId + "]"
                + ",\"supportedAnchor\":\"\",\"reason\":\"The complete citation anchor is supported.\"}]}";
    }

    private Suggestion suggestion(Long id) {
        Suggestion suggestion = new Suggestion(1L, "ADVOCACY", "RelatedWork", "Ground this claim.");
        ReflectionTestUtils.setField(suggestion, "id", id);
        suggestion.setApplicable(false);
        suggestion.setStatus("PROPOSED");
        suggestion.setPatchJson("{\"citationCritic\":{\"verdict\":\"REJECTED\",\"reason\":\"Too broad.\"}}");
        return suggestion;
    }

    private LiteratureCard card(Long id, String title) {
        LiteratureCard card = new LiteratureCard("hash-" + id, title);
        ReflectionTestUtils.setField(card, "id", id);
        card.setAbstractText(title + " in polarimetric radar systems.");
        card.setAnalysisJson("{\"support\":\"direct\"}");
        card.setPublicationYear(2024);
        card.setVenue("Journal");
        return card;
    }

    private LatexDocument document() {
        String tex = "\\begin{document}\n\\section{Introduction}\n"
                + FIRST_ORIGINAL + "\n" + SECOND_ORIGINAL
                + "\n\\section{Method}\nThe method is described here.\n\\end{document}";
        return new LatexParserService().parse("main.tex", tex, Map.of());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void verifyPersisted(PaperCitationClosurePersistenceService persistence,
                                 PaperCitationClosureService.ClosureResult expected) {
        ArgumentCaptor<PaperCitationClosureService.ClosureResult> captor =
                ArgumentCaptor.forClass(PaperCitationClosureService.ClosureResult.class);
        verify(persistence).persist(org.mockito.ArgumentMatchers.eq(1L), captor.capture());
        assertThat(captor.getValue()).isEqualTo(expected);
    }

    private record Harness(PaperCitationClosureService service,
                           PaperCitationClosurePersistenceService persistence) {}
}
