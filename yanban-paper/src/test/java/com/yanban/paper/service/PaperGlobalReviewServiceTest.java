package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.LatexSectionRole;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PaperGlobalReviewServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void suppressesUnprovenDimensionConflictAndEquationPunctuationWarning() throws Exception {
        String matrix = "\\mathbf{T}\\in\\mathbb{C}^{2\\times M}.";
        String gram = "\\mathbf{G}_t=\\mathbf{T}^H\\mathbf{T}\\in\\mathbb{C}^{M\\times M}.";
        String punctuatedEquation = "\\mathbf{A}_{single}=\\mathbf{a}_{single}\\mathbf{a}_{single}^H.";
        String draft = "\\section{Method}\n" + matrix + "\n" + gram + "\n" + punctuatedEquation;
        String response = objectMapper.writeValueAsString(Map.of("issues", List.of(
                issue("NOTATION", "DIMENSION_CONFLICT", "The dimensions of T are inconsistent.", List.of(evidence(matrix), evidence(gram))),
                issue("FORMULA", "FORMULA_PROSE_MISMATCH", "A stray period appears before the equation end.", List.of(evidence(punctuatedEquation)))
        )));
        TestHarness harness = harness(response);

        Map<String, Object> result = harness.service().reviewAndSave(harness.task(), document(draft), draft, List.of());

        assertThat(result.get("issueCount")).isEqualTo(0);
        assertThat(result.get("suppressedIssueCount")).isEqualTo(2);
        ArgumentCaptor<Map<String, Object>> promptVariables = ArgumentCaptor.forClass(Map.class);
        verify(harness.promptService()).render(eq("global-review"), promptVariables.capture());
        assertThat(String.valueOf(promptVariables.getValue().get("sectionDossier"))).contains(matrix, gram);
    }

    @Test
    void keepsDimensionConflictWhenEvidenceProvesTheMatrixProductIsInvalid() throws Exception {
        String matrix = "\\mathbf{T}\\in\\mathbb{C}^{M\\times 2}.";
        String gram = "\\mathbf{G}_t=\\mathbf{T}^H\\mathbf{T}\\in\\mathbb{C}^{M\\times M}.";
        String draft = "\\section{Method}\n" + matrix + "\n" + gram;
        String response = objectMapper.writeValueAsString(Map.of("issues", List.of(
                issue("NOTATION", "DIMENSION_CONFLICT", "The declared Gram-matrix dimension is inconsistent.", List.of(evidence(matrix), evidence(gram)))
        )));
        TestHarness harness = harness(response);

        Map<String, Object> result = harness.service().reviewAndSave(harness.task(), document(draft), draft, List.of());

        assertThat(result.get("issueCount")).isEqualTo(1);
        assertThat((List<?>) result.get("issues")).singleElement().satisfies(value -> {
            Map<?, ?> issue = (Map<?, ?>) value;
            assertThat(issue.get("verified")).isEqualTo(true);
            assertThat(issue.get("ruleId")).isEqualTo("DIMENSION_CONFLICT");
        });
    }

    @Test
    void suppressesIssueWhoseQuotedEvidenceDoesNotExistInFinalDraft() throws Exception {
        String draft = "\\section{Method}\nThe final polished method text.";
        String response = objectMapper.writeValueAsString(Map.of("issues", List.of(
                issue("LOGIC", "NARRATIVE_GAP", "Unsupported issue.", List.of(evidence("Text that is not in the manuscript.")))
        )));
        TestHarness harness = harness(response);

        Map<String, Object> result = harness.service().reviewAndSave(harness.task(), document(draft), draft, List.of());

        assertThat(result.get("issueCount")).isEqualTo(0);
        assertThat(result.get("suppressedIssueCount")).isEqualTo(1);
    }

    private TestHarness harness(String response) {
        PaperTaskAnalysisRepository analyses = mock(PaperTaskAnalysisRepository.class);
        PaperPromptService promptService = mock(PaperPromptService.class);
        PaperModelClient modelClient = (systemPrompt, userPrompt, temperature, maxTokens) -> response;
        PaperTask task = new PaperTask(1L, "Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "GLOBAL_REVIEW", null);
        ReflectionTestUtils.setField(task, "id", 42L);
        PaperTaskAnalysis analysis = new PaperTaskAnalysis(42L);
        analysis.setResearchProfileJson("{}");
        analysis.setConceptLadderJson("{}");
        analysis.setGapMatrixJson("{}");
        when(analyses.findByTaskId(42L)).thenReturn(Optional.of(analysis));
        when(promptService.render(eq("global-review"), anyMap())).thenReturn("prompt");
        PaperGlobalReviewService service = new PaperGlobalReviewService(analyses, promptService, modelClient, objectMapper);
        return new TestHarness(service, promptService, task);
    }

    private Map<String, Object> issue(String type, String ruleId, String message, List<Map<String, Object>> evidence) {
        return Map.of(
                "type", type,
                "ruleId", ruleId,
                "sectionIds", List.of(0),
                "severity", "major",
                "message", message,
                "suggestedFix", "Verify the definitions.",
                "autoFixAllowed", false,
                "evidence", evidence
        );
    }

    private Map<String, Object> evidence(String quote) {
        return Map.of("sectionOrder", 0, "equationLabel", "", "quote", quote);
    }

    private LatexDocument document(String raw) {
        LatexSection section = new LatexSection(0, 1, "section", true, "Method", LatexSectionRole.METHOD, 0, raw.length(), raw);
        return new LatexDocument("main.tex", "Paper", List.of(), List.of(), "", "", List.of(section),
                List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }

    private record TestHarness(PaperGlobalReviewService service, PaperPromptService promptService, PaperTask task) {}
}
