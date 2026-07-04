package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.literature.LiteratureSearchTaskService;
import com.yanban.paper.service.PaperOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class TaskControlServiceTest {

    private static final Long USER_ID = 10L;
    private static final Long TASK_ID = 7L;

    private PaperTaskRepository paperTaskRepository;
    private PaperOrchestrator paperOrchestrator;
    private LiteratureSearchTaskRepository literatureTaskRepository;
    private LiteratureSearchTaskService literatureTaskService;
    private TaskControlService service;

    @BeforeEach
    void setUp() {
        paperTaskRepository = mock(PaperTaskRepository.class);
        paperOrchestrator = mock(PaperOrchestrator.class);
        literatureTaskRepository = mock(LiteratureSearchTaskRepository.class);
        literatureTaskService = mock(LiteratureSearchTaskService.class);
        service = new TaskControlService(
                paperTaskRepository,
                paperOrchestrator,
                literatureTaskRepository,
                literatureTaskService
        );
    }

    @Test
    void cancelPaperTaskByTypeStopsRunningTask() {
        PaperTask paperTask = paperTask("RUNNING", "POLISH");
        when(paperTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(paperTask));

        TaskCancelResponse response = service.cancel(USER_ID, TASK_ID, "paper_polish", "stop now");

        assertThat(response.taskType()).isEqualTo("paper_polish");
        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.cancelAccepted()).isTrue();
        assertThat(response.idempotent()).isFalse();
        assertThat(response.beforeStatus()).isEqualTo("RUNNING");
        assertThat(response.afterStatus()).isNotBlank();
        verify(paperOrchestrator).stop(USER_ID, TASK_ID);
    }

    @Test
    void cancelPaperTaskByTypeIsIdempotentWhenTerminal() {
        PaperTask paperTask = paperTask("COMPLETED", "COMPLETE");
        when(paperTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(paperTask));

        TaskCancelResponse response = service.cancel(USER_ID, TASK_ID, "PAPER", null);

        assertThat(response.cancelAccepted()).isFalse();
        assertThat(response.idempotent()).isTrue();
        assertThat(response.beforeStatus()).isEqualTo("COMPLETED");
        verify(paperOrchestrator, never()).stop(USER_ID, TASK_ID);
    }

    @Test
    void cancelPaperTaskByTypeIsIdempotentWhenCancellingRequested() {
        PaperTask paperTask = paperTask("CANCEL_REQUESTED", "POLISH");
        when(paperTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(paperTask));

        TaskCancelResponse response = service.cancel(USER_ID, TASK_ID, "paper_polish", null);

        assertThat(response.cancelAccepted()).isFalse();
        assertThat(response.idempotent()).isTrue();
        assertThat(response.beforeStatus()).isEqualTo("CANCEL_REQUESTED");
        verify(paperOrchestrator, never()).stop(USER_ID, TASK_ID);
    }

    @Test
    void cancelLiteratureTaskByTypeCancelsRunningTask() {
        LiteratureSearchTask literatureTask = literatureSearchTask("RUNNING", "SEARCHING");
        when(literatureTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(literatureTask));
        LiteratureSearchTask afterCancel = literatureSearchTask("CANCEL_REQUESTED", "CANCEL_REQUESTED");
        ReflectionTestUtils.setField(afterCancel, "id", TASK_ID);
        when(literatureTaskService.requestCancel(eq(USER_ID), eq(TASK_ID), eq("user canceled"))).thenReturn(afterCancel);
        when(literatureTaskService.isTerminal(eq("RUNNING"))).thenReturn(false);

        TaskCancelResponse response = service.cancel(USER_ID, TASK_ID, "literature_search", "user canceled");

        assertThat(response.taskType()).isEqualTo("literature_search");
        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.cancelAccepted()).isTrue();
        assertThat(response.idempotent()).isFalse();
        assertThat(response.beforeStatus()).isEqualTo("RUNNING");
        assertThat(response.afterStatus()).isEqualTo("CANCEL_REQUESTED");
        verify(literatureTaskService).requestCancel(USER_ID, TASK_ID, "user canceled");
    }

    @Test
    void cancelLiteratureTaskByTypeIsIdempotentWhenCancellingRequested() {
        LiteratureSearchTask literatureTask = literatureSearchTask("CANCEL_REQUESTED", "SEARCHING");
        when(literatureTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(literatureTask));

        TaskCancelResponse response = service.cancel(USER_ID, TASK_ID, "LITERATURE_SEARCH", null);

        assertThat(response.cancelAccepted()).isFalse();
        assertThat(response.idempotent()).isTrue();
        assertThat(response.beforeStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(response.afterStatus()).isEqualTo("CANCEL_REQUESTED");
        verify(literatureTaskService, never()).requestCancel(USER_ID, TASK_ID, null);
    }

    @Test
    void cancelPaperByAutoDetectFailsWhenNotFound() {
        when(paperTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.empty());
        when(literatureTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.empty());

        try {
            service.cancel(USER_ID, TASK_ID, null, null);
            throw new AssertionError("Expected ResponseStatusException");
        } catch (ResponseStatusException ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void getPaperTaskStatusByType() {
        PaperTask paperTask = paperTask("RUNNING", "POLISH");
        when(paperTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(paperTask));

        TaskStatusResponse response = service.getStatus(USER_ID, TASK_ID, "paper_polish");

        assertThat(response.taskType()).isEqualTo("paper_polish");
        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.status()).isEqualTo("RUNNING");
        assertThat(response.currentStage()).isEqualTo("POLISH");
        assertThat(response.terminal()).isFalse();
        assertThat(response.cancellable()).isTrue();
    }

    @Test
    void getPaperTaskStatusCancellingIsNotCancellable() {
        PaperTask paperTask = paperTask("CANCEL_REQUESTED", "POLISH");
        when(paperTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(paperTask));

        TaskStatusResponse response = service.getStatus(USER_ID, TASK_ID, "paper_polish");

        assertThat(response.taskType()).isEqualTo("paper_polish");
        assertThat(response.status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(response.terminal()).isFalse();
        assertThat(response.cancellable()).isFalse();
    }

    @Test
    void getLiteratureTaskStatusAutoDetect() {
        when(paperTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.empty());
        LiteratureSearchTask literatureTask = literatureSearchTask("CANCELLED", "CANCELLED");
        when(literatureTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(literatureTask));
        when(literatureTaskService.isTerminal("CANCELLED")).thenReturn(true);

        TaskStatusResponse response = service.getStatus(USER_ID, TASK_ID, null);

        assertThat(response.taskType()).isEqualTo("literature_search");
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.terminal()).isTrue();
        assertThat(response.cancellable()).isFalse();
    }

    @Test
    void getLiteratureTaskStatusCancellingIsNotCancellable() {
        when(paperTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.empty());
        LiteratureSearchTask literatureTask = literatureSearchTask("CANCELLING", "CANCELLING");
        when(literatureTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(literatureTask));

        TaskStatusResponse response = service.getStatus(USER_ID, TASK_ID, "literature_search");

        assertThat(response.status()).isEqualTo("CANCELLING");
        assertThat(response.terminal()).isFalse();
        assertThat(response.cancellable()).isFalse();
    }

    @Test
    void autoDetectRejectsAmbiguousTaskId() {
        when(paperTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(paperTask("RUNNING", "POLISH")));
        when(literatureTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(literatureSearchTask("RUNNING", "SEARCHING")));

        try {
            service.cancel(USER_ID, TASK_ID, null, null);
            throw new AssertionError("Expected ResponseStatusException");
        } catch (ResponseStatusException ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void cancelAutoDetectChoosesPaperWhenPaperExistsAndLiteratureMissing() {
        PaperTask paperTask = paperTask("RUNNING", "PARSE");
        when(paperTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.of(paperTask));
        when(literatureTaskRepository.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(java.util.Optional.empty());

        service.cancel(USER_ID, TASK_ID, null, null);

        verify(paperOrchestrator).stop(USER_ID, TASK_ID);
    }

    private PaperTask paperTask(String status, String stage) {
        PaperTask task = new PaperTask(USER_ID, "paper", "paper.tex", "paper/object.tex", status, "zh", stage, null);
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        return task;
    }

    private LiteratureSearchTask literatureSearchTask(String status, String stage) {
        LiteratureSearchTask task = new LiteratureSearchTask(
                USER_ID,
                null,
                "query",
                "query",
                8,
                null,
                true,
                status,
                stage,
                "req-1",
                "idem"
        );
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        return task;
    }
}
