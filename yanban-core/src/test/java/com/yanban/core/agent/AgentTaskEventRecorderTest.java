package com.yanban.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentTaskEventRecorderTest {

    private AgentTaskEventWriter writer;
    private AgentTaskEventRepository events;
    private AgentTaskEventRecorder recorder;

    @BeforeEach
    void setUp() {
        writer = mock(AgentTaskEventWriter.class);
        events = mock(AgentTaskEventRepository.class);
        recorder = new AgentTaskEventRecorder(writer, events);
    }

    @Test
    void recordSafelyIgnoresInvalidRequest() {
        recorder.recordSafely(new AgentTaskEventCreateRequest(
                "",
                10L,
                1001L,
                "TASK_CREATED",
                "QUEUED",
                "PENDING",
                null,
                null
        ));

        verify(writer, never()).record(any());
    }

    @Test
    void recordSafelyDoesNotThrowWhenWriterFails() {
        AgentTaskEventCreateRequest request = request();
        when(writer.record(request)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> recorder.recordSafely(request)).doesNotThrowAnyException();
    }

    @Test
    void listEventsReturnsEmptyWhenKeyIsIncomplete() {
        assertThat(recorder.listEvents("", 1L, 1L)).isEmpty();
        assertThat(recorder.listEvents(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH, null, 1L)).isEmpty();
        assertThat(recorder.listEvents(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH, 1L, null)).isEmpty();
    }

    @Test
    void listEventsDelegatesToRepositoryInCreatedOrder() {
        AgentTaskEvent event = new AgentTaskEvent(
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                10L,
                1001L,
                "TASK_CREATED",
                "QUEUED",
                "PENDING",
                null,
                null
        );
        when(events.findByTaskTypeAndTaskIdAndUserIdOrderByCreatedAtAsc(
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                10L,
                1001L
        )).thenReturn(List.of(event));

        assertThat(recorder.listEvents(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH, 10L, 1001L))
                .containsExactly(event);
    }

    private AgentTaskEventCreateRequest request() {
        return new AgentTaskEventCreateRequest(
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                10L,
                1001L,
                "TASK_CREATED",
                "QUEUED",
                "PENDING",
                "created",
                null
        );
    }
}
