package com.yanban.paper.literature;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.core.agent.AgentTaskRegistry;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class LiteratureSearchTaskUnifiedTaskMirrorTest {

    private static final Long USER_ID = 11L;
    private static final Long TASK_ID = 88L;

    private LiteratureSearchTaskRepository tasks;
    private AgentTaskRegistry registry;
    private LiteratureSearchTaskService service;

    @BeforeEach
    void setUp() {
        tasks = mock(LiteratureSearchTaskRepository.class);
        registry = mock(AgentTaskRegistry.class);
        service = new LiteratureSearchTaskService(tasks, provider(null), provider(null), provider(registry));
    }

    @Test
    void createTaskSyncsPendingUnifiedTaskMirror() {
        when(tasks.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> {
            LiteratureSearchTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", TASK_ID);
            return task;
        });

        service.createTask(USER_ID, new LiteratureSearchTaskRequest("hybrid RAG", 6, null, true, "req-1", 7L));

        verify(registry).upsertSafely(argThat(request ->
                request.sourceId().equals(TASK_ID)
                        && request.status().equals(LiteratureSearchTaskService.STATUS_PENDING)
                        && request.taskType().equals(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH)
                        && request.source().equals(AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK)
        ));
    }

    @Test
    void markFailedSyncsTerminalUnifiedTaskMirror() {
        LiteratureSearchTask task = new LiteratureSearchTask(USER_ID, 7L, "hybrid RAG", "hybrid rag", 6, null, true,
                LiteratureSearchTaskService.STATUS_RUNNING, "SEARCHING", "req-1", "idem-1");
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.markFailed(USER_ID, TASK_ID, "timeout");

        verify(registry).upsertSafely(argThat(request ->
                request.sourceId().equals(TASK_ID)
                        && request.status().equals(LiteratureSearchTaskService.STATUS_FAILED)
                        && "timeout".equals(request.errorMessage())
        ));
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
