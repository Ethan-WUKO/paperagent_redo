package com.yanban.paper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexMaskingService;
import com.yanban.paper.latex.LatexParserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = PaperCitationClosurePipelineIntegrationTest.TestConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PaperCitationClosurePipelineIntegrationTest {

    private static final String ORIGINAL =
            "Prior work broadly studies polarimetric target estimation under all operating conditions.";
    private static final String REPLACEMENT =
            "Prior work studies polarimetric target estimation in radar systems.";
    private static final String NO_FIT_SENTENCE =
            "A universal joint design solves every polarimetric radar limitation.";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {
            PaperTaskAnalysis.class, LiteratureCard.class, Suggestion.class, SuggestionEvidence.class})
    @EnableJpaRepositories(basePackageClasses = {
            PaperTaskAnalysisRepository.class, LiteratureCardRepository.class,
            SuggestionRepository.class, SuggestionEvidenceRepository.class})
    static class TestConfig {}

    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidence;
    private final LiteratureCardRepository cards;
    private final PaperTaskAnalysisRepository analyses;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    PaperCitationClosurePipelineIntegrationTest(SuggestionRepository suggestions,
                                                SuggestionEvidenceRepository evidence,
                                                LiteratureCardRepository cards,
                                                PaperTaskAnalysisRepository analyses) {
        this.suggestions = suggestions;
        this.evidence = evidence;
        this.cards = cards;
        this.analyses = analyses;
    }

    @Test
    void persistsClosureThenRebuildsTexAndBibFromDatabaseState() {
        Long taskId = 77L;
        PaperTaskAnalysis analysis = new PaperTaskAnalysis(taskId);
        analysis.setGapMatrixJson("{\"citationCritic\":{\"withheldCount\":2}}");
        analyses.save(analysis);

        LiteratureCard supported = card("supported", "Supported Estimation Work", "10.1000/supported");
        LiteratureCard unused = card("unused", "Adjacent but Unused Work", "10.1000/unused");
        LiteratureCard noFit = card("no-fit", "Unrelated Universal Design Work", "10.1000/no-fit");
        supported = cards.save(supported);
        unused = cards.save(unused);
        noFit = cards.save(noFit);

        Suggestion repairable = suggestion(taskId, "Repair the broad estimation claim.", "PARTIAL");
        Suggestion rejected = suggestion(taskId, "Force the universal design paper into the Introduction.", "REJECTED");
        repairable = suggestions.save(repairable);
        rejected = suggestions.save(rejected);
        evidence.save(new SuggestionEvidence(repairable.getId(), supported.getId()));
        evidence.save(new SuggestionEvidence(repairable.getId(), unused.getId()));
        evidence.save(new SuggestionEvidence(rejected.getId(), noFit.getId()));

        Long repairableId = repairable.getId();
        Long rejectedId = rejected.getId();
        Long supportedId = supported.getId();
        Long noFitId = noFit.getId();
        PaperModelClient model = (system, prompt, temperature, maxTokens) -> {
            if (prompt.startsWith("# Full-Introduction Citation Closure Critic")) {
                return "{\"diagnoses\":["
                        + "{\"suggestionId\":" + repairableId + ",\"action\":\"NARROW\","
                        + "\"supportedEvidenceCardIds\":[" + supportedId + "],"
                        + "\"supportedFact\":\"Polarimetric target estimation is studied in radar systems.\","
                        + "\"unsupportedQualifiers\":[\"broadly\",\"all operating conditions\"],"
                        + "\"placementGuidance\":\"Narrow the first sentence.\",\"reason\":\"Only the narrower fact is supported.\"},"
                        + "{\"suggestionId\":" + rejectedId + ",\"action\":\"NO_FIT\","
                        + "\"supportedEvidenceCardIds\":[],\"supportedFact\":\"\","
                        + "\"unsupportedQualifiers\":[],\"placementGuidance\":\"\","
                        + "\"reason\":\"The evidence has no safe fit in the current Introduction.\"}]}";
            }
            if (prompt.startsWith("# Introduction Citation Orator")) {
                return "{\"patches\":[{\"suggestionId\":" + repairableId
                        + ",\"decision\":\"APPLY\",\"operation\":\"NARROW\","
                        + "\"originalAnchor\":\"" + ORIGINAL + "\","
                        + "\"replacementText\":\"" + REPLACEMENT + "\","
                        + "\"citationAnchor\":\"" + REPLACEMENT + "\","
                        + "\"reason\":\"The narrower sentence preserves the argument.\"}]}";
            }
            if (prompt.startsWith("# Citation Closure Verification")) {
                return "{\"verifications\":[{\"suggestionId\":" + repairableId
                        + ",\"verdict\":\"SUPPORTED\",\"acceptedEvidenceCardIds\":[" + supportedId + "],"
                        + "\"supportedAnchor\":\"\",\"reason\":\"The complete narrowed claim is supported.\"}]}";
            }
            throw new AssertionError("Unexpected prompt: " + prompt);
        };
        PaperCitationClosurePersistenceService persistence = new PaperCitationClosurePersistenceService(
                suggestions, evidence, analyses, objectMapper);
        PaperCitationClosureService closure = new PaperCitationClosureService(
                suggestions, evidence, cards, new PaperPromptService(), model,
                new LatexMaskingService(), objectMapper, persistence);
        LatexDocument document = new LatexParserService().parse(
                "main.tex",
                "\\begin{document}\n\\section{Introduction}\n" + ORIGINAL + "\n" + NO_FIT_SENTENCE
                        + "\n\\section{Method}\nMethod text.\n\\end{document}",
                Map.of());

        PaperCitationClosureService.ClosureResult closureResult = closure.close(taskId, document);

        assertThat(closureResult.acceptedCount()).isEqualTo(1);
        assertThat(closureResult.reportOnlyCount()).isEqualTo(1);
        Suggestion storedRepairable = suggestions.findById(repairableId).orElseThrow();
        Suggestion storedRejected = suggestions.findById(rejectedId).orElseThrow();
        assertThat(storedRepairable.getStatus()).isEqualTo("ACCEPTED");
        assertThat(storedRejected.getStatus()).isEqualTo("PROPOSED");
        assertThat(evidence.findBySuggestionId(repairableId))
                .extracting(SuggestionEvidence::getCardId)
                .containsExactly(supportedId);

        List<Suggestion> persistedSuggestions = suggestions.findByTaskIdOrderByCreatedAt(taskId);
        Map<Long, LiteratureCard> cardById = cards.findAllById(List.of(supported.getId(), unused.getId(), noFit.getId()))
                .stream().collect(Collectors.toMap(LiteratureCard::getId, Function.identity()));
        Map<Long, List<LiteratureCard>> evidenceBySuggestion = new LinkedHashMap<>();
        for (Suggestion item : persistedSuggestions) {
            evidenceBySuggestion.put(item.getId(), evidence.findBySuggestionId(item.getId()).stream()
                    .map(link -> cardById.get(link.getCardId()))
                    .filter(java.util.Objects::nonNull)
                    .toList());
        }
        PaperCitationApplyService.CitationApplyResult applied = new PaperCitationApplyService(objectMapper).apply(
                "\\section{Introduction}\n" + ORIGINAL + "\n" + NO_FIT_SENTENCE,
                "", "references.bib", Map.of(), persistedSuggestions, evidenceBySuggestion);

        assertThat(applied.polishedTex())
                .doesNotContain(ORIGINAL)
                .contains(REPLACEMENT.substring(0, REPLACEMENT.length() - 1) + " \\cite{")
                .contains(NO_FIT_SENTENCE);
        assertThat(applied.mergedBib())
                .contains("Supported Estimation Work", "YANBAN_RECOMMENDED_BEGIN")
                .doesNotContain("Adjacent but Unused Work", "Unrelated Universal Design Work");
        assertThat(applied.appliedPatches()).hasSize(1);
        assertThat(applied.manualPatches()).isEmpty();
        assertThat(analyses.findByTaskId(taskId).orElseThrow().getGapMatrixJson())
                .contains("citationClosureLoop", "\"acceptedCount\":1", "\"reportOnlyCount\":1");
    }

    private Suggestion suggestion(Long taskId, String statement, String verdict) {
        Suggestion suggestion = new Suggestion(taskId, "ADVOCACY", "RelatedWork", statement);
        suggestion.setApplicable(false);
        suggestion.setStatus("PROPOSED");
        suggestion.setPatchJson("{\"citationCritic\":{\"verdict\":\"" + verdict + "\"}}");
        return suggestion;
    }

    private LiteratureCard card(String hash, String title, String doi) {
        LiteratureCard card = new LiteratureCard(hash, title);
        card.setDoi(doi);
        card.setAuthors("[\"Alice Smith\"]");
        card.setPublicationYear(2024);
        card.setVenue("Journal");
        card.setAbstractText(title + " in radar systems.");
        card.setAnalysisJson("{\"support\":\"direct\"}");
        return card;
    }
}
