package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexParserService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaperResearchProfileServiceTest {

    @Mock
    private PaperTaskRepository tasks;

    @Mock
    private PaperTaskAnalysisRepository analysisRepository;

    private final PaperPromptService promptService = new PaperPromptService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseValidProfileJson() {
        PaperResearchProfileService service = serviceWithProvider("{}");

        ResearchProfileResult result = service.parseResult("""
                {
                  "problem": "retrieval augmentation",
                  "method": "hybrid retrieval",
                  "contributions": ["pipeline"],
                  "datasets": ["DemoSet"],
                  "baselines": ["BM25"],
                  "metrics": ["MRR"],
                  "tasks": ["document QA"],
                  "keywords": ["RAG", "LaTeX"]
                }
                """);

        assertThat(result.degraded()).isFalse();
        assertThat(result.problem()).isEqualTo("retrieval augmentation");
        assertThat(result.contributions()).containsExactly("pipeline");
        assertThat(result.keywords()).contains("RAG", "LaTeX");
    }

    @Test
    void parseInvalidJsonAsDegraded() {
        PaperResearchProfileService service = serviceWithProvider("{}");

        ResearchProfileResult result = service.parseResult("not-json");

        assertThat(result.degraded()).isTrue();
        assertThat(result.rawText()).isEqualTo("not-json");
    }

    @Test
    void generateAndSaveProfile() {
        String responseJson = """
                {"problem":"problem","method":"method","contributions":["c1"],
                 "datasets":[],"baselines":[],"metrics":[],"tasks":["task"],"keywords":["k"]}
                """;
        PaperResearchProfileService service = serviceWithProvider(responseJson);
        PaperTask task = new PaperTask(7L, "Demo", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null);
        when(tasks.findById(1L)).thenReturn(Optional.of(task));
        when(analysisRepository.findByTaskId(1L)).thenReturn(Optional.empty());
        when(analysisRepository.save(any(PaperTaskAnalysis.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LatexDocument document = new LatexParserService().parse("main.tex", """
                \\documentclass{article}
                \\title{Demo}
                \\begin{document}
                \\section{Introduction}
                This paper studies retrieval augmented generation.
                \\end{document}
                """, Map.of());

        ResearchProfileResult result = service.generateAndSave(1L, document, "en");

        assertThat(result.degraded()).isFalse();
        assertThat(result.problem()).isEqualTo("problem");
        ArgumentCaptor<PaperTaskAnalysis> captor = ArgumentCaptor.forClass(PaperTaskAnalysis.class);
        org.mockito.Mockito.verify(analysisRepository).save(captor.capture());
        assertThat(captor.getValue().getResearchProfileJson()).contains("problem", "c1");
    }

    private PaperResearchProfileService serviceWithProvider(String assistantText) {
        return new PaperResearchProfileService(tasks, analysisRepository, promptService, new StubProvider(assistantText), objectMapper);
    }

    private static final class StubProvider implements PaperModelClient {
        private final String assistantText;

        private StubProvider(String assistantText) {
            this.assistantText = assistantText;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, Double temperature, Integer maxTokens) {
            return assistantText;
        }
    }
}
