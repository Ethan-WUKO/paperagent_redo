package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaperCitationCriticServiceTest {

    private static final Pattern BATCH_INDEX = Pattern.compile("\\\"batchIndex\\\":(\\d+)");
    private static final Pattern EXPECTED_IDS = Pattern.compile("\\\"expectedSuggestionIds\\\":\\[([^]]*)]");

    @Test
    void keepsOnlySupportedKnownEvidenceIds() {
        PaperPromptService prompts = prompts();
        PaperModelClient model = (system, user, temperature, maxTokens) -> """
                {"decisions":[{"suggestionId":41,"verdict":"SUPPORTED","acceptedEvidenceCardIds":[7,999],"reason":"Card 7 directly supports the exact claim."}]}
                """;
        PaperCitationCriticService service = new PaperCitationCriticService(prompts, model, new ObjectMapper());

        PaperCitationCriticService.CitationCriticResult result = service.review(
                List.of(suggestion(41L, List.of(7L, 8L))),
                Map.of(7L, card(7L), 8L, card(8L)));

        assertThat(result.degraded()).isFalse();
        assertThat(result.closureStatus()).isEqualTo("PASS");
        assertThat(result.decisions().get(41L).supported()).isTrue();
        assertThat(result.decisions().get(41L).acceptedEvidenceCardIds()).containsExactly(7L);
    }

    @Test
    void acceptsOnlyTheExactSupportedClauseFromAPartialDecisionWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        PaperModelClient model = (system, user, temperature, maxTokens) -> {
            calls.incrementAndGet();
            return """
                    {"decisions":[
                      {"suggestionId":42,"verdict":"PARTIAL","acceptedEvidenceCardIds":[7],"supportedAnchor":"An exact manuscript claim","reason":"The venue qualifier is unsupported."},
                      {"suggestionId":43,"verdict":"REJECTED","acceptedEvidenceCardIds":[],"reason":"The paper is only topically related."}
                    ]}
                    """;
        };
        PaperCitationCriticService service = new PaperCitationCriticService(prompts(), model, new ObjectMapper());

        PaperCitationCriticService.CitationCriticResult result = service.review(
                List.of(suggestion(42L, List.of(7L), "An exact manuscript claim, but a venue qualifier is asserted."),
                        suggestion(43L, List.of(8L))),
                Map.of(7L, card(7L), 8L, card(8L)));

        assertThat(calls).hasValue(1);
        assertThat(result.closureStatus()).isEqualTo("PASS");
        assertThat(result.decisions().get(42L).verdict()).isEqualTo("PARTIAL");
        assertThat(result.decisions().get(42L).supported()).isTrue();
        assertThat(result.decisions().get(42L).supportedAnchor()).isEqualTo("An exact manuscript claim");
        assertThat(result.decisions().get(42L).acceptedEvidenceCardIds()).containsExactly(7L);
        assertThat(result.decisions().get(43L).verdict()).isEqualTo("REJECTED");
        assertThat(result.decisions().get(43L).supported()).isFalse();
    }

    @Test
    void withholdsPartialEvidenceWhenTheReturnedTextIsNotACompleteClause() {
        PaperModelClient model = (system, user, temperature, maxTokens) -> """
                {"decisions":[{"suggestionId":45,"verdict":"PARTIAL","acceptedEvidenceCardIds":[7],
                "supportedAnchor":"manuscript claim with a venue","reason":"Only a narrower claim is supported."}]}
                """;
        PaperCitationCriticService service = new PaperCitationCriticService(prompts(), model, new ObjectMapper());

        PaperCitationCriticService.CitationDecision decision = service.review(
                List.of(suggestion(45L, List.of(7L))), Map.of(7L, card(7L))).decisions().get(45L);

        assertThat(decision.verdict()).isEqualTo("PARTIAL");
        assertThat(decision.supported()).isFalse();
        assertThat(decision.acceptedEvidenceCardIds()).isEmpty();
        assertThat(decision.supportedAnchor()).isEmpty();
    }

    @Test
    void splitsThirteenSuggestionsAndRetriesOnlyMalformedSecondBatch() {
        Fixture fixture = fixture(13);
        Map<Integer, Integer> callsByBatch = new LinkedHashMap<>();
        PaperModelClient model = (system, user, temperature, maxTokens) -> {
            int batch = batchIndex(user);
            int calls = callsByBatch.merge(batch, 1, Integer::sum);
            if (batch == 2 && calls == 1) return "{\"wrong\":[]}";
            return supportedResponse(user, fixture.cardBySuggestion());
        };
        PaperCitationCriticService service = new PaperCitationCriticService(prompts(), model, new ObjectMapper());

        PaperCitationCriticService.CitationCriticResult result = service.review(fixture.suggestions(), fixture.cards());

        assertThat(callsByBatch).containsExactly(
                Map.entry(1, 1), Map.entry(2, 2), Map.entry(3, 1), Map.entry(4, 1));
        assertThat(result.closureStatus()).isEqualTo("PASS");
        assertThat(result.decisions()).hasSize(13);
        assertThat(result.decisions().values()).allMatch(PaperCitationCriticService.CitationDecision::supported);
        assertThat(result.batches()).extracting(PaperCitationCriticService.BatchDiagnostic::suggestionIds)
                .containsExactly(List.of(1L, 2L, 3L, 4L), List.of(5L, 6L, 7L, 8L),
                        List.of(9L, 10L, 11L, 12L), List.of(13L));
        assertThat(result.batches().get(1).attemptCount()).isEqualTo(2);
        assertThat(result.batches().get(1).parseMode()).isEqualTo("REPAIRED_JSON");
    }

    @Test
    void failedRetryMarksOnlyThatBatchUnavailable() {
        Fixture fixture = fixture(9);
        Map<Integer, Integer> callsByBatch = new LinkedHashMap<>();
        PaperModelClient model = (system, user, temperature, maxTokens) -> {
            int batch = batchIndex(user);
            callsByBatch.merge(batch, 1, Integer::sum);
            if (batch == 2) return "{\"wrong\":[]}";
            return supportedResponse(user, fixture.cardBySuggestion());
        };
        PaperCitationCriticService service = new PaperCitationCriticService(prompts(), model, new ObjectMapper());

        PaperCitationCriticService.CitationCriticResult result = service.review(fixture.suggestions(), fixture.cards());

        assertThat(callsByBatch).containsExactly(Map.entry(1, 1), Map.entry(2, 2), Map.entry(3, 1));
        assertThat(result.closureStatus()).isEqualTo("PARTIAL");
        assertThat(result.decisions().entrySet())
                .filteredOn(entry -> entry.getKey() >= 5 && entry.getKey() <= 8)
                .allSatisfy(entry -> assertThat(entry.getValue().verdict()).isEqualTo("UNREVIEWED"));
        assertThat(result.decisions().entrySet())
                .filteredOn(entry -> entry.getKey() <= 4 || entry.getKey() == 9)
                .allSatisfy(entry -> assertThat(entry.getValue().supported()).isTrue());
        assertThat(result.summary()).containsEntry("failedBatchCount", 1L)
                .containsEntry("successfulBatchCount", 2L)
                .containsEntry("retriedBatchCount", 1L);
    }

    @Test
    void unknownSuggestionIdCannotBeAccepted() {
        AtomicInteger calls = new AtomicInteger();
        PaperModelClient model = (system, user, temperature, maxTokens) -> {
            calls.incrementAndGet();
            return """
                    {"decisions":[{"suggestionId":999,"verdict":"SUPPORTED","acceptedEvidenceCardIds":[7],"reason":"Invalid id."}]}
                    """;
        };
        PaperCitationCriticService service = new PaperCitationCriticService(prompts(), model, new ObjectMapper());

        PaperCitationCriticService.CitationCriticResult result = service.review(
                List.of(suggestion(44L, List.of(7L))), Map.of(7L, card(7L)));

        assertThat(calls).hasValue(2);
        assertThat(result.closureStatus()).isEqualTo("DEGRADED");
        assertThat(result.decisions().get(44L).verdict()).isEqualTo("UNREVIEWED");
    }

    private PaperPromptService prompts() {
        PaperPromptService prompts = mock(PaperPromptService.class);
        when(prompts.render(eq("citation-critic"), anyMap())).thenAnswer(invocation -> {
            Map<?, ?> values = invocation.getArgument(1);
            return String.valueOf(values.get("candidates"));
        });
        return prompts;
    }

    private Fixture fixture(int count) {
        List<GapSuggestionResult> suggestions = new ArrayList<>();
        Map<Long, LiteratureCard> cards = new LinkedHashMap<>();
        Map<Long, Long> cardBySuggestion = new LinkedHashMap<>();
        for (long id = 1; id <= count; id++) {
            long cardId = 1000 + id;
            suggestions.add(suggestion(id, List.of(cardId)));
            cards.put(cardId, card(cardId));
            cardBySuggestion.put(id, cardId);
        }
        return new Fixture(List.copyOf(suggestions), Map.copyOf(cards), Map.copyOf(cardBySuggestion));
    }

    private String supportedResponse(String prompt, Map<Long, Long> cardBySuggestion) {
        List<String> decisions = expectedIds(prompt).stream()
                .map(id -> "{\"suggestionId\":" + id
                        + ",\"verdict\":\"SUPPORTED\",\"acceptedEvidenceCardIds\":["
                        + cardBySuggestion.get(id) + "],\"reason\":\"Direct support.\"}")
                .toList();
        return "{\"decisions\":[" + String.join(",", decisions) + "]}";
    }

    private int batchIndex(String prompt) {
        Matcher matcher = BATCH_INDEX.matcher(prompt);
        if (!matcher.find()) throw new IllegalArgumentException("Missing batch index in prompt: " + prompt);
        return Integer.parseInt(matcher.group(1));
    }

    private List<Long> expectedIds(String prompt) {
        Matcher matcher = EXPECTED_IDS.matcher(prompt);
        if (!matcher.find()) throw new IllegalArgumentException("Missing expected ids in prompt: " + prompt);
        if (matcher.group(1).isBlank()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (String value : matcher.group(1).split(",")) ids.add(Long.parseLong(value.trim()));
        return ids;
    }

    private GapSuggestionResult suggestion(Long id, List<Long> evidence) {
        return suggestion(id, evidence, "An exact manuscript claim with a venue qualifier.");
    }

    private GapSuggestionResult suggestion(Long id, List<Long> evidence, String anchor) {
        return new GapSuggestionResult(id, "ADVOCACY", "RelatedWork", "minor", "Support the exact claim.",
                evidence, true, Map.of("targetSlotId", "slot-" + id, "anchor", anchor));
    }

    private LiteratureCard card(Long id) {
        LiteratureCard card = new LiteratureCard("hash-" + id, "Evidence " + id);
        ReflectionTestUtils.setField(card, "id", id);
        card.setVenue("Journal");
        card.setPublicationYear(2024);
        card.setAbstractText("This abstract directly describes the supported method and evaluation setting.");
        return card;
    }

    private record Fixture(List<GapSuggestionResult> suggestions,
                           Map<Long, LiteratureCard> cards,
                           Map<Long, Long> cardBySuggestion) {}
}
