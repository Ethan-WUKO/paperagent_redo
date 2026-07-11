package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.security.JwtUser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentTaskEventControllerTest {

    @Test
    void listEventsUsesCurrentUser() {
        AgentTaskEventService service = mock(AgentTaskEventService.class);
        AgentTaskEventController controller = new AgentTaskEventController(service);
        List<AgentTaskEventResponse> expected = List.of(new AgentTaskEventResponse(
                1L,
                "LITERATURE_SEARCH",
                101L,
                11L,
                "TASK_CREATED",
                "QUEUED",
                "PENDING",
                "created",
                null,
                Instant.parse("2026-07-04T01:00:00Z")
        ));
        when(service.listEvents(11L, "LITERATURE_SEARCH", 101L, null, null)).thenReturn(expected);

        List<AgentTaskEventResponse> actual =
                controller.listEvents(new JwtUser(11L, "alice"), "LITERATURE_SEARCH", 101L, null, null);

        assertThat(actual).isSameAs(expected);
        verify(service).listEvents(11L, "LITERATURE_SEARCH", 101L, null, null);
    }

    @Test
    void listEventsPassesCursorParameters() {
        AgentTaskEventService service = mock(AgentTaskEventService.class);
        AgentTaskEventController controller = new AgentTaskEventController(service);
        when(service.listEvents(11L, "paper-polish", 101L, 99L, 20)).thenReturn(List.of());

        List<AgentTaskEventResponse> actual =
                controller.listEvents(new JwtUser(11L, "alice"), "paper-polish", 101L, 99L, 20);

        assertThat(actual).isEmpty();
        verify(service).listEvents(11L, "paper-polish", 101L, 99L, 20);
    }

    @Test
    void streamEventsPassesQueryParameters() {
        AgentTaskEventService service = mock(AgentTaskEventService.class);
        AgentTaskEventController controller = new AgentTaskEventController(service);
        SseEmitter expected = new SseEmitter(0L);
        when(service.streamEvents(11L, "literature-search", 101L, 99L, 20, 1500L)).thenReturn(expected);

        SseEmitter actual = controller.streamEvents(
                new JwtUser(11L, "alice"),
                "literature-search",
                101L,
                99L,
                20,
                1500L,
                null
        );

        assertThat(actual).isSameAs(expected);
        verify(service).streamEvents(11L, "literature-search", 101L, 99L, 20, 1500L);
    }

    @Test
    void streamEventsParsesLastEventIdHeader() {
        AgentTaskEventService service = mock(AgentTaskEventService.class);
        AgentTaskEventController controller = new AgentTaskEventController(service);
        SseEmitter expected = new SseEmitter(0L);
        when(service.streamEvents(11L, "paper-polish", 101L, 99L, null, null)).thenReturn(expected);

        SseEmitter actual = controller.streamEvents(
                new JwtUser(11L, "alice"),
                "paper-polish",
                101L,
                null,
                null,
                null,
                "99"
        );

        assertThat(actual).isSameAs(expected);
        verify(service).streamEvents(11L, "paper-polish", 101L, 99L, null, null);
    }
}
