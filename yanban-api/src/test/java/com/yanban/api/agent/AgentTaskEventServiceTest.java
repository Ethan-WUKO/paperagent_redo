package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.agent.AgentTaskEvent;
import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.literature.LiteratureSearchTaskService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class AgentTaskEventServiceTest {

    private static final Long USER_ID = 11L;
    private static final Long TASK_ID = 101L;

    private AgentTaskEventRecorder events;
    private LiteratureSearchTaskService literatureTasks;
    private PaperTaskRepository paperTasks;
    private AgentTaskEventService service;

    @BeforeEach
    void setUp() {
        events = mock(AgentTaskEventRecorder.class);
        literatureTasks = mock(LiteratureSearchTaskService.class);
        paperTasks = mock(PaperTaskRepository.class);
        service = new AgentTaskEventService(events, literatureTasks, paperTasks);
    }

    @Test
    void listEventsReturnsOwnedLiteratureEventsInRecorderOrder() {
        when(literatureTasks.getTask(USER_ID, TASK_ID)).thenReturn(mock(LiteratureSearchTask.class));
        AgentTaskEvent created = event(1L, "TASK_CREATED", Instant.parse("2026-07-04T01:00:00Z"));
        AgentTaskEvent running = event(2L, "TASK_RUNNING", Instant.parse("2026-07-04T01:00:01Z"));
        when(events.listEvents(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH, TASK_ID, USER_ID))
                .thenReturn(List.of(created, running));

        List<AgentTaskEventResponse> responses = service.listEvents(USER_ID, "literature-search", TASK_ID);

        assertThat(responses).extracting(AgentTaskEventResponse::eventType)
                .containsExactly("TASK_CREATED", "TASK_RUNNING");
        assertThat(responses.get(0).taskType()).isEqualTo(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH);
        verify(literatureTasks).getTask(USER_ID, TASK_ID);
        verify(events).listEvents(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH, TASK_ID, USER_ID);
    }

    @Test
    void listEventsReturnsEmptyWhenTaskHasNoEvents() {
        when(literatureTasks.getTask(USER_ID, TASK_ID)).thenReturn(mock(LiteratureSearchTask.class));
        when(events.listEvents(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH, TASK_ID, USER_ID))
                .thenReturn(List.of());

        assertThat(service.listEvents(USER_ID, "LITERATURE_SEARCH", TASK_ID)).isEmpty();
    }

    @Test
    void listEventsRejectsUnsupportedTaskType() {
        assertThatThrownBy(() -> service.listEvents(USER_ID, "UNKNOWN_TASK", TASK_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(literatureTasks, never()).getTask(USER_ID, TASK_ID);
        verify(events, never()).listEvents(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH, TASK_ID, USER_ID);
    }

    @Test
    void listEventsReturnsOwnedPaperPolishEvents() {
        when(paperTasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(mock(PaperTask.class)));
        AgentTaskEvent created = event(1L, AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH, "TASK_CREATED",
                Instant.parse("2026-07-04T01:00:00Z"));
        AgentTaskEvent completed = event(2L, AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH, "TASK_COMPLETED",
                Instant.parse("2026-07-04T01:01:00Z"));
        when(events.listEvents(AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH, TASK_ID, USER_ID))
                .thenReturn(List.of(created, completed));

        List<AgentTaskEventResponse> responses = service.listEvents(USER_ID, "paper-polish", TASK_ID);

        assertThat(responses).extracting(AgentTaskEventResponse::eventType)
                .containsExactly("TASK_CREATED", "TASK_COMPLETED");
        assertThat(responses.get(0).taskType()).isEqualTo(AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH);
        verify(paperTasks).findByIdAndUserId(TASK_ID, USER_ID);
        verify(literatureTasks, never()).getTask(USER_ID, TASK_ID);
    }

    @Test
    void listEventsPropagatesNotFoundWhenPaperTaskIsNotOwned() {
        when(paperTasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listEvents(USER_ID, "PAPER_POLISH", TASK_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(events, never()).listEvents(AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH, TASK_ID, USER_ID);
    }

    @Test
    void listEventsPropagatesNotFoundWhenTaskIsNotOwned() {
        when(literatureTasks.getTask(USER_ID, TASK_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "文献检索任务不存在或不可访问"));

        assertThatThrownBy(() -> service.listEvents(USER_ID, "LITERATURE_SEARCH", TASK_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(events, never()).listEvents(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH, TASK_ID, USER_ID);
    }

    private AgentTaskEvent event(Long id, String eventType, Instant createdAt) {
        return event(id, AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH, eventType, createdAt);
    }

    private AgentTaskEvent event(Long id, String taskType, String eventType, Instant createdAt) {
        AgentTaskEvent event = new AgentTaskEvent(
                taskType,
                TASK_ID,
                USER_ID,
                eventType,
                "QUEUED",
                "PENDING",
                eventType,
                null
        );
        ReflectionTestUtils.setField(event, "id", id);
        ReflectionTestUtils.setField(event, "createdAt", createdAt);
        return event;
    }
}
