package com.yanban.api.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentTaskEvent;
import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.core.agent.AgentTaskEventRepository;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AgentObservabilityServiceTest {

    private AgentPlanRepository plans;
    private AgentPlanEventRepository events;
    private AgentTaskEventRepository taskEvents;
    private LiteratureSearchTaskRepository literatureTasks;
    private AgentObservabilityService service;

    @BeforeEach
    void setUp() {
        plans = mock(AgentPlanRepository.class);
        events = mock(AgentPlanEventRepository.class);
        taskEvents = mock(AgentTaskEventRepository.class);
        literatureTasks = mock(LiteratureSearchTaskRepository.class);
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setLiteraturePendingBacklogWarning(1);
        properties.setLiteraturePendingBacklogCritical(2);
        properties.setLiteratureRunningAgeWarningSeconds(60);
        properties.setLiteratureRunningAgeCriticalSeconds(120);
        properties.setLiteratureTimeoutEventWarning(1);
        properties.setLiteratureTimeoutEventCritical(2);
        service = new AgentObservabilityService(plans, events, taskEvents, literatureTasks, properties);
    }

    @Test
    void dashboardIncludesLiteratureLifecycleMetrics() {
        when(plans.findByCreatedAtAfterOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(events.findByCreatedAtAfterOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(taskEvents.findByCreatedAtAfterOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                taskEvent("TASK_REQUEUED"),
                taskEvent("TASK_MANUAL_REQUEUED"),
                taskEvent("TASK_TIMED_OUT")
        ));
        when(literatureTasks.findByCreatedAtAfterOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                literatureTask("PENDING", null, Instant.now().minusSeconds(300)),
                literatureTask("RUNNING", Instant.now().minusSeconds(600), Instant.now().minusSeconds(700)),
                literatureTask("COMPLETED", Instant.now().minusSeconds(120), Instant.now().minusSeconds(240))
        ));

        AgentObservabilityService.DashboardResponse response = service.dashboard(60);

        assertThat(response.literatureTaskStatusCounts()).containsEntry("PENDING", 1L);
        assertThat(response.literatureTaskStatusCounts()).containsEntry("RUNNING", 1L);
        assertThat(response.literatureLifecycleEventCounts()).containsEntry("TASK_REQUEUED", 1L);
        assertThat(response.literatureLifecycleEventCounts()).containsEntry("TASK_MANUAL_REQUEUED", 1L);
        assertThat(response.literatureLifecycleEventCounts()).containsEntry("TASK_TIMED_OUT", 1L);
        assertThat(response.literaturePendingCount()).isEqualTo(1L);
        assertThat(response.literatureRunningCount()).isEqualTo(1L);
        assertThat(response.literatureOldestPendingAgeMs()).isGreaterThan(0L);
        assertThat(response.literatureOldestRunningAgeMs()).isGreaterThan(0L);
    }

    @Test
    void alertsIncludeLiteratureBacklogRunningAgeAndTimeoutSignals() {
        when(plans.findByCreatedAtAfterOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(events.findByCreatedAtAfterOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(taskEvents.findByCreatedAtAfterOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                taskEvent("TASK_TIMED_OUT"),
                taskEvent("TASK_TIMED_OUT")
        ));
        when(literatureTasks.findByCreatedAtAfterOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                literatureTask("PENDING", null, Instant.now().minusSeconds(180)),
                literatureTask("PENDING", null, Instant.now().minusSeconds(240)),
                literatureTask("RUNNING", Instant.now().minusSeconds(300), Instant.now().minusSeconds(360))
        ));

        AgentObservabilityService.AlertResponse response = service.alerts(60);

        assertThat(response.alerts()).extracting(AgentObservabilityService.AlertItem::id)
                .contains("literature_pending_backlog", "literature_running_age", "literature_timeout_events");
        assertThat(response.alerts().stream()
                .filter(item -> "literature_pending_backlog".equals(item.id()))
                .findFirst()
                .orElseThrow()
                .severity()).isEqualTo("CRITICAL");
        assertThat(response.alerts().stream()
                .filter(item -> "literature_timeout_events".equals(item.id()))
                .findFirst()
                .orElseThrow()
                .severity()).isEqualTo("CRITICAL");
    }

    private AgentTaskEvent taskEvent(String eventType) {
        AgentTaskEvent event = new AgentTaskEvent(
                AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH,
                101L,
                11L,
                eventType,
                "SEARCHING",
                "RUNNING",
                eventType,
                null
        );
        ReflectionTestUtils.setField(event, "createdAt", Instant.now().minusSeconds(30));
        return event;
    }

    private LiteratureSearchTask literatureTask(String status, Instant startedAt, Instant createdAt) {
        LiteratureSearchTask task = new LiteratureSearchTask(
                11L,
                null,
                "query",
                "query",
                8,
                null,
                true,
                status,
                status,
                "req-1",
                "idem-" + status
        );
        task.setStartedAt(startedAt);
        ReflectionTestUtils.setField(task, "createdAt", createdAt);
        ReflectionTestUtils.setField(task, "updatedAt", createdAt.atZone(ZoneId.systemDefault()).toInstant());
        return task;
    }
}
