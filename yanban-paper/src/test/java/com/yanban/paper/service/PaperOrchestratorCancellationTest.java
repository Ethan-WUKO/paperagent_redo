package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.agent.AgentTaskEventCreateRequest;
import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskClarificationRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.PaperTaskRoundRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexParserService;
import com.yanban.paper.latex.LatexRoleRecognitionService;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.LatexSectionRole;
import com.yanban.paper.literature.LiteratureService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class PaperOrchestratorCancellationTest {

    private static final Long USER_ID = 7L;
    private static final Long TASK_ID = 42L;

    private PaperTaskRepository tasks;
    private PaperSectionRepository sections;
    private PaperSectionPolishService sectionPolishService;
    private PaperEventStreamService eventStreamService;
    private AgentTaskEventRecorder taskEvents;
    private PaperOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        tasks = mock(PaperTaskRepository.class);
        sections = mock(PaperSectionRepository.class);
        sectionPolishService = mock(PaperSectionPolishService.class);
        eventStreamService = mock(PaperEventStreamService.class);
        taskEvents = mock(AgentTaskEventRecorder.class);
        orchestrator = new PaperOrchestrator(
                tasks,
                mock(PaperTaskRoundRepository.class),
                sections,
                mock(PaperTaskArtifactRepository.class),
                mock(PaperTaskClarificationRepository.class),
                eventStreamService,
                mock(PaperStorageService.class),
                mock(LatexParserService.class),
                mock(LatexRoleRecognitionService.class),
                mock(PaperClarificationService.class),
                mock(PaperResearchProfileService.class),
                mock(PaperIntroductionAnalysisService.class),
                mock(LiteratureService.class),
                mock(PaperGapAnalysisService.class),
                sectionPolishService,
                mock(PaperAssembleService.class),
                directExecutor(),
                taskEvents
        );
    }

    @Test
    void stopNonRunningTaskCancelsImmediately() {
        PaperTask task = task("RUNNING", "POLISH");
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));

        orchestrator.stop(USER_ID, TASK_ID);

        assertThat(task.getStatus()).isEqualTo(PaperOrchestrator.STATUS_CANCELLED);
        assertThat(task.getCurrentStage()).isEqualTo(PaperOrchestrator.STAGE_CANCELLED);
        assertThat(task.getErrorMessage()).isEqualTo("任务已取消");
        assertEventTypes("cancel_requested", "cancelled");
        assertRecordedEventTypes("TASK_CANCEL_REQUESTED", "TASK_CANCELLED");
    }

    @Test
    void stopRunningTaskRequestsCancellationUntilCheckpoint() {
        PaperTask task = task("RUNNING", "RETRIEVE");
        markRunning(TASK_ID);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));

        orchestrator.stop(USER_ID, TASK_ID);

        assertThat(task.getStatus()).isEqualTo(PaperOrchestrator.STATUS_CANCEL_REQUESTED);
        assertThat(task.getCurrentStage()).isEqualTo("RETRIEVE");
        assertThat(task.getErrorMessage()).isNull();
        assertEventTypes("cancel_requested");
        assertRecordedEventTypes("TASK_CANCEL_REQUESTED");
    }

    @Test
    void checkpointMovesRequestedCancellationToCancelling() {
        PaperTask task = task(PaperOrchestrator.STATUS_CANCEL_REQUESTED, "RETRIEVE");
        markRunning(TASK_ID);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        orchestrator.stop(USER_ID, TASK_ID);

        assertThatThrownBy(() -> orchestrator.checkpoint(TASK_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("任务已取消");

        assertThat(task.getStatus()).isEqualTo(PaperOrchestrator.STATUS_CANCELLING);
        assertThat(task.getCurrentStage()).isEqualTo("RETRIEVE");
        assertEventTypes("cancel_requested", "cancelling");
        assertRecordedEventTypes("TASK_CANCEL_REQUESTED", "TASK_CANCELLING");
    }

    @Test
    void transitionCancelledWritesFinalCancelledEvent() {
        PaperTask task = task(PaperOrchestrator.STATUS_CANCELLING, "RETRIEVE");
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));

        orchestrator.transitionCancelled(TASK_ID, "任务已取消");

        assertThat(task.getStatus()).isEqualTo(PaperOrchestrator.STATUS_CANCELLED);
        assertThat(task.getCurrentStage()).isEqualTo(PaperOrchestrator.STAGE_CANCELLED);
        assertThat(task.getErrorMessage()).isEqualTo("任务已取消");
        assertEventTypes("cancelled");
        assertRecordedEventTypes("TASK_CANCELLED");
    }

    @Test
    void stopTerminalTaskIsIdempotent() {
        PaperTask task = task(PaperOrchestrator.STATUS_COMPLETED, "COMPLETE");
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));

        orchestrator.stop(USER_ID, TASK_ID);

        assertThat(task.getStatus()).isEqualTo(PaperOrchestrator.STATUS_COMPLETED);
        assertThat(task.getCurrentStage()).isEqualTo("COMPLETE");
        assertNoEvents();
        verify(taskEvents, never()).recordSafely(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void stopRejectsTaskOwnedByAnotherUser() {
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.stop(USER_ID, TASK_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertNoEvents();
    }

    @Test
    void polishSectionsUsesPersistedConfigAndRecordsSectionException() {
        PaperTask task = task("RUNNING", "POLISH");
        task.setScoreThreshold(92);
        task.setMaxRounds(4);
        task.setInnerMaxAttempts(5);
        LatexSection latexSection = new LatexSection(0, 2, "section", true, "Introduction", LatexSectionRole.INTRO, 0, 42,
                "\\section{Introduction}\nText.");
        LatexDocument document = new LatexDocument("main.tex", "paper", List.of(), List.of(), "", "", List.of(latexSection),
                List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
        PaperSection stored = new PaperSection(TASK_ID, "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 42);

        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(sectionPolishService).polishSection(TASK_ID, latexSection, "zh", 92, 4, 5);
        when(sections.findByTaskIdOrderByOrderIndexAsc(TASK_ID)).thenReturn(List.of(stored));

        Integer processed = ReflectionTestUtils.invokeMethod(orchestrator, "polishSections", task, document);

        assertThat(processed).isEqualTo(1);
        verify(sectionPolishService).polishSection(TASK_ID, latexSection, "zh", 92, 4, 5);
        assertThat(stored.getPolishStatus()).isEqualTo("FAILED_KEEP_ORIGINAL");
        assertThat(stored.getReviewJson()).contains("section_polish_exception", "\"maxRounds\":4", "\"innerMaxAttempts\":5");
        verify(sections).save(stored);
    }

    private PaperTask task(String status, String stage) {
        PaperTask task = new PaperTask(USER_ID, "paper", "main.tex", "paper/main.tex", status, "zh", stage, null);
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        return task;
    }

    @SuppressWarnings("unchecked")
    private void markRunning(Long taskId) {
        Set<Long> runningTasks = (Set<Long>) ReflectionTestUtils.getField(orchestrator, "runningTasks");
        assertThat(runningTasks).isNotNull();
        runningTasks.add(taskId);
    }

    private void assertEventTypes(String... types) {
        ArgumentCaptor<PaperSseEvent> captor = ArgumentCaptor.forClass(PaperSseEvent.class);
        verify(eventStreamService, org.mockito.Mockito.times(types.length)).publish(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(PaperSseEvent::type)
                .containsExactly(types);
    }

    private void assertRecordedEventTypes(String... types) {
        ArgumentCaptor<AgentTaskEventCreateRequest> captor = ArgumentCaptor.forClass(AgentTaskEventCreateRequest.class);
        verify(taskEvents, org.mockito.Mockito.times(types.length)).recordSafely(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AgentTaskEventCreateRequest::eventType)
                .containsExactly(types);
        assertThat(captor.getAllValues())
                .extracting(AgentTaskEventCreateRequest::taskType)
                .containsOnly(AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH);
    }

    private void assertNoEvents() {
        verify(eventStreamService, org.mockito.Mockito.never()).publish(org.mockito.ArgumentMatchers.any());
    }

    private Executor directExecutor() {
        return Runnable::run;
    }
}
