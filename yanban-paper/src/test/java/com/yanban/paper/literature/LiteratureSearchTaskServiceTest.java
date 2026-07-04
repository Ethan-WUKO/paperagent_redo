package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class LiteratureSearchTaskServiceTest {

    private static final Long USER_ID = 11L;
    private static final Long TASK_ID = 101L;

    private LiteratureSearchTaskRepository tasks;
    private LiteratureSearchTaskService service;

    @BeforeEach
    void setUp() {
        tasks = mock(LiteratureSearchTaskRepository.class);
        service = new LiteratureSearchTaskService(tasks);
    }

    @Test
    void createTaskNormalizesInputAndSavesPendingTask() {
        when(tasks.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> {
            LiteratureSearchTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", TASK_ID);
            return task;
        });

        LiteratureSearchTaskService.TaskStartResult result = service.createTask(
                USER_ID,
                new LiteratureSearchTaskRequest("  hybrid   RAG  ", 99, 2021, null, "req-1", 7L)
        );

        assertThat(result.idempotent()).isFalse();
        assertThat(result.task().getId()).isEqualTo(TASK_ID);
        assertThat(result.task().getQuery()).isEqualTo("hybrid RAG");
        assertThat(result.task().getTopK()).isEqualTo(20);
        assertThat(result.task().getIncludeBibtex()).isTrue();
        assertThat(result.task().getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_PENDING);
        assertThat(result.task().getCurrentStage()).isEqualTo("QUEUED");
        assertThat(result.task().getProjectId()).isEqualTo(7L);
    }

    @Test
    void duplicateClientRequestReturnsExistingTask() {
        LiteratureSearchTask existing = task(LiteratureSearchTaskService.STATUS_PENDING);
        when(tasks.findByIdempotencyKey(any())).thenReturn(Optional.of(existing));

        LiteratureSearchTaskService.TaskStartResult result = service.createTask(
                USER_ID,
                new LiteratureSearchTaskRequest("hybrid RAG", 8, null, true, "req-1", null)
        );

        assertThat(result.idempotent()).isTrue();
        assertThat(result.task()).isSameAs(existing);
        verify(tasks, never()).save(any());
    }

    @Test
    void requestCancelMovesNonTerminalTaskToCancelRequested() {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_RUNNING);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LiteratureSearchTask cancelled = service.requestCancel(USER_ID, TASK_ID, "user stopped");

        assertThat(cancelled.getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_CANCEL_REQUESTED);
        assertThat(cancelled.getCurrentStage()).isEqualTo("CANCEL_REQUESTED");
        assertThat(cancelled.getCancelReason()).isEqualTo("user stopped");
    }

    @Test
    void requestCancelTerminalTaskIsIdempotent() {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_COMPLETED);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));

        LiteratureSearchTask result = service.requestCancel(USER_ID, TASK_ID, "too late");

        assertThat(result.getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_COMPLETED);
        verify(tasks, never()).save(any());
    }

    @Test
    void inaccessibleTaskThrowsNotFound() {
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.empty());

        try {
            service.getTask(USER_ID, TASK_ID);
        } catch (ResponseStatusException ex) {
            assertThat(ex.getReason()).contains("不可访问");
            return;
        }
        throw new AssertionError("Expected ResponseStatusException");
    }

    private LiteratureSearchTask task(String status) {
        LiteratureSearchTask task = new LiteratureSearchTask(
                USER_ID,
                null,
                "hybrid RAG",
                "hybrid rag",
                8,
                null,
                true,
                status,
                "QUEUED",
                "req-1",
                "idem"
        );
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        return task;
    }
}
