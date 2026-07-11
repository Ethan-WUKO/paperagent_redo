package com.yanban.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentTaskRegistryTest {

    private AgentTaskRepository tasks;
    private AgentTaskRegistry registry;

    @BeforeEach
    void setUp() {
        tasks = mock(AgentTaskRepository.class);
        registry = new AgentTaskRegistry(tasks);
    }

    @Test
    void upsertCreatesNewTaskWhenMirrorDoesNotExist() {
        AgentTaskUpsertRequest request = request("PENDING", "QUEUED", null, null);
        when(tasks.findByTaskTypeAndSourceAndSourceId(
                request.taskType(),
                request.source(),
                request.sourceId()
        )).thenReturn(Optional.empty());
        when(tasks.save(any(AgentTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentTask saved = registry.upsert(request);

        assertThat(saved.getTaskType()).isEqualTo(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH);
        assertThat(saved.getSource()).isEqualTo(AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK);
        assertThat(saved.getSourceId()).isEqualTo(42L);
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getCurrentStage()).isEqualTo("QUEUED");
        assertThat(saved.getProjectId()).isEqualTo(9L);
    }

    @Test
    void upsertUpdatesExistingMirrorWithoutCreatingDuplicate() {
        AgentTask existing = new AgentTask(
                11L,
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK,
                42L,
                "PENDING"
        );
        existing.setCurrentStage("QUEUED");
        when(tasks.findByTaskTypeAndSourceAndSourceId(
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK,
                42L
        )).thenReturn(Optional.of(existing));
        when(tasks.save(existing)).thenReturn(existing);

        AgentTask saved = registry.upsert(request("COMPLETED", "COMPLETE", null, Instant.parse("2026-07-05T00:00:00Z")));

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getStatus()).isEqualTo("COMPLETED");
        assertThat(saved.getCurrentStage()).isEqualTo("COMPLETE");
        assertThat(saved.getFinishedAt()).isEqualTo(Instant.parse("2026-07-05T00:00:00Z"));
    }

    @Test
    void upsertSafelyIgnoresIncompleteRequest() {
        registry.upsertSafely(new AgentTaskUpsertRequest(
                null, null, "", "", null, "", null, null,
                null, null, null, null, null, null, null, null, null, null, null
        ));

        verify(tasks, never()).save(any());
    }

    @Test
    void upsertSafelySwallowsRepositoryFailure() {
        AgentTaskUpsertRequest request = request("RUNNING", "SEARCHING", null, null);
        when(tasks.findByTaskTypeAndSourceAndSourceId(
                request.taskType(),
                request.source(),
                request.sourceId()
        )).thenReturn(Optional.empty());
        when(tasks.save(any(AgentTask.class))).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> registry.upsertSafely(request)).doesNotThrowAnyException();
    }

    private AgentTaskUpsertRequest request(String status,
                                           String currentStage,
                                           Instant startedAt,
                                           Instant finishedAt) {
        return new AgentTaskUpsertRequest(
                11L,
                9L,
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK,
                42L,
                status,
                "LONG_RUNNING_TOOL_TASK",
                "req-1",
                "hybrid RAG",
                "hybrid RAG",
                "COMPLETED".equals(status) ? 100 : null,
                currentStage,
                null,
                null,
                null,
                0,
                0,
                startedAt,
                finishedAt
        );
    }
}
