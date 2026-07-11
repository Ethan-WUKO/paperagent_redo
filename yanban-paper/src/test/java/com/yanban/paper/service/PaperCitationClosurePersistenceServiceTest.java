package com.yanban.paper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceId;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperCitationClosurePersistenceServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void persistsEachOutcomeIndependentlyAndRetainsOnlyApprovedEvidence() throws Exception {
        SuggestionRepository suggestions = mock(SuggestionRepository.class);
        SuggestionEvidenceRepository evidence = mock(SuggestionEvidenceRepository.class);
        PaperTaskAnalysisRepository analyses = mock(PaperTaskAnalysisRepository.class);
        Suggestion accepted = suggestion(1L);
        Suggestion reportOnly = suggestion(2L);
        PaperTaskAnalysis analysis = new PaperTaskAnalysis(7L);
        analysis.setGapMatrixJson("{\"citationCritic\":{\"candidateCount\":2}}");
        when(suggestions.findAllById(List.of(1L, 2L))).thenReturn(List.of(accepted, reportOnly));
        when(evidence.findBySuggestionId(1L)).thenReturn(List.of(
                new SuggestionEvidence(1L, 11L),
                new SuggestionEvidence(1L, 12L)));
        when(analyses.findByTaskId(7L)).thenReturn(Optional.of(analysis));
        PaperCitationClosurePersistenceService service = new PaperCitationClosurePersistenceService(
                suggestions, evidence, analyses, objectMapper);
        String longReason = "x".repeat(12_000);

        PaperCitationClosureService.ClosureOutcome acceptedOutcome = new PaperCitationClosureService.ClosureOutcome(
                1L, true, 2, "NARROW", "Original supported claim sentence.",
                "Narrow supported claim sentence.", "Narrow supported claim sentence.",
                List.of(11L), Map.of("verdict", "SUPPORTED", "reason", longReason),
                longReason, 0, "Introduction", List.of(Map.of("round", 1), Map.of("round", 2)));
        PaperCitationClosureService.ClosureOutcome reportOutcome = new PaperCitationClosureService.ClosureOutcome(
                2L, false, 3, "NONE", "", "", "", List.of(), Map.of(),
                "No safe supported placement.", 0, "Introduction", List.of(Map.of("round", 3)));
        PaperCitationClosureService.ClosureResult result = new PaperCitationClosureService.ClosureResult(
                "COMPLETED", 2, 1, 1, 1, List.of(acceptedOutcome, reportOutcome), "Done");

        service.persist(7L, result);

        assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
        assertThat(accepted.getApplicable()).isTrue();
        assertThat(accepted.getPatchJson()).hasSizeLessThanOrEqualTo(9_800);
        Map<String, Object> acceptedPatch = readMap(accepted.getPatchJson());
        assertThat(acceptedPatch).containsEntry("decisionSource", "AUTO_CITATION_CLOSURE")
                .containsEntry("anchor", "Narrow supported claim sentence.");
        Map<?, ?> acceptedClosure = (Map<?, ?>) acceptedPatch.get("citationClosure");
        assertThat(acceptedClosure.get("status")).isEqualTo("SUPPORTED");
        assertThat(acceptedClosure.get("attempts")).isEqualTo(2);
        assertThat(reportOnly.getStatus()).isEqualTo("PROPOSED");
        assertThat(reportOnly.getApplicable()).isFalse();
        Map<?, ?> reportClosure = (Map<?, ?>) readMap(reportOnly.getPatchJson()).get("citationClosure");
        assertThat(reportClosure.get("status")).isEqualTo("REPORT_ONLY");
        assertThat(reportClosure.get("attempts")).isEqualTo(3);
        verify(evidence).deleteById(new SuggestionEvidenceId(1L, 12L));
        Map<String, Object> matrix = readMap(analysis.getGapMatrixJson());
        Map<?, ?> closureLoop = (Map<?, ?>) matrix.get("citationClosureLoop");
        assertThat(closureLoop.get("acceptedCount")).isEqualTo(1);
        assertThat(closureLoop.get("reportOnlyCount")).isEqualTo(1);
        verify(suggestions).save(accepted);
        verify(suggestions).save(reportOnly);
        verify(analyses).save(analysis);
    }

    private Suggestion suggestion(Long id) {
        Suggestion suggestion = new Suggestion(7L, "ADVOCACY", "RelatedWork", "Ground this claim.");
        ReflectionTestUtils.setField(suggestion, "id", id);
        suggestion.setPatchJson("{\"citationCritic\":{\"verdict\":\"REJECTED\"},\"applicationDecision\":{\"status\":\"NO_STABLE_ANCHOR\"}}");
        return suggestion;
    }

    private Map<String, Object> readMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }
}
