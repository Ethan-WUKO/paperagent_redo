package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.core.agent.AgentTaskRegistry;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskClarificationRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.PaperTaskRoundRepository;
import com.yanban.paper.latex.LatexParserService;
import com.yanban.paper.latex.LatexRoleRecognitionService;
import com.yanban.paper.literature.LiteratureService;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaperOrchestratorUnifiedTaskMirrorTest {

    private static final Long USER_ID = 9L;
    private static final Long TASK_ID = 42L;

    private PaperTaskRepository tasks;
    private AgentTaskRegistry agentTaskRegistry;
    private PaperOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        tasks = mock(PaperTaskRepository.class);
        agentTaskRegistry = mock(AgentTaskRegistry.class);
        orchestrator = new PaperOrchestrator(
                tasks,
                mock(PaperTaskRoundRepository.class),
                mock(PaperSectionRepository.class),
                mock(PaperTaskArtifactRepository.class),
                mock(PaperTaskClarificationRepository.class),
                mock(PaperEventStreamService.class),
                mock(PaperStorageService.class),
                mock(LatexParserService.class),
                mock(LatexRoleRecognitionService.class),
                mock(PaperClarificationService.class),
                mock(PaperResearchProfileService.class),
                mock(PaperIntroductionAnalysisService.class),
                mock(LiteratureService.class),
                mock(PaperGapAnalysisService.class),
                mock(PaperSectionPolishService.class),
                mock(PaperAssembleService.class),
                directExecutor(),
                mock(AgentTaskEventRecorder.class),
                agentTaskRegistry
        );
    }

    @Test
    void transitionCompleteSyncsUnifiedTaskMirror() {
        PaperTask task = new PaperTask(USER_ID, "Hybrid RAG", "draft.tex", "paper/source.tex", "RUNNING", "en", "POLISH", null);
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));

        orchestrator.transitionComplete(TASK_ID, "paper/final.tex");

        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        verify(agentTaskRegistry).upsertSafely(argThat(request ->
                request.sourceId().equals(TASK_ID)
                        && request.status().equals("COMPLETED")
                        && request.taskType().equals(AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH)
                        && request.source().equals(AgentTaskRegistry.SOURCE_PAPER_TASK)
                        && request.progressPercent().equals(100)
        ));
    }

    private Executor directExecutor() {
        return Runnable::run;
    }
}
