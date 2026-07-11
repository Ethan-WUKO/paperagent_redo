package com.yanban.api.observability;

import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanStatus;
import com.yanban.core.agent.AgentTaskEvent;
import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.core.agent.AgentTaskEventRepository;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AgentObservabilityService {

    private static final List<String> GUARDRAIL_EVENTS = List.of(
            "plan_budget_exceeded",
            "step_tool_budget_exceeded",
            "step_duplicate_tool_call_blocked",
            "plan_final_verification_failed",
            "step_verification_failed",
            "step_verification_inconclusive"
    );
    private static final List<String> LITERATURE_MONITOR_EVENTS = List.of(
            "TASK_REQUEUED",
            "TASK_MANUAL_REQUEUED",
            "TASK_TIMED_OUT"
    );

    private final AgentPlanRepository plans;
    private final AgentPlanEventRepository events;
    private final AgentTaskEventRepository taskEvents;
    private final LiteratureSearchTaskRepository literatureTasks;
    private final ObservabilityProperties properties;

    public AgentObservabilityService(AgentPlanRepository plans,
                                     AgentPlanEventRepository events,
                                     AgentTaskEventRepository taskEvents,
                                     LiteratureSearchTaskRepository literatureTasks,
                                     ObservabilityProperties properties) {
        this.plans = plans;
        this.events = events;
        this.taskEvents = taskEvents;
        this.literatureTasks = literatureTasks;
        this.properties = properties;
    }

    public DashboardResponse dashboard(Integer requestedWindowMinutes) {
        WindowData data = loadWindow(requestedWindowMinutes);
        LiteratureLifecycleSummary literature = literatureSummary(data);
        List<PlanSummary> slowPlans = data.plans().stream()
                .filter(plan -> durationMs(plan) != null)
                .sorted(Comparator.comparingLong((AgentPlan plan) -> durationMs(plan)).reversed())
                .limit(10)
                .map(plan -> new PlanSummary(
                        plan.getId(),
                        plan.getStatus(),
                        durationMs(plan),
                        plan.getStartedAt(),
                        plan.getFinishedAt(),
                        safeError(plan.getErrorMessage())
                ))
                .toList();
        List<PlanSummary> runningPlans = data.plans().stream()
                .filter(plan -> AgentPlanStatus.RUNNING.name().equals(plan.getStatus()))
                .sorted(Comparator.comparing(AgentPlan::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(10)
                .map(plan -> new PlanSummary(
                        plan.getId(),
                        plan.getStatus(),
                        runningAgeMs(plan, data.now()),
                        plan.getStartedAt(),
                        plan.getFinishedAt(),
                        safeError(plan.getErrorMessage())
                ))
                .toList();
        return new DashboardResponse(
                data.now(),
                data.windowMinutes(),
                data.plans().size(),
                statusCounts(data.plans()),
                eventCounts(data.events()),
                terminalCount(data.plans()),
                failureRate(data.plans()),
                averageDurationMs(data.plans()),
                percentileDurationMs(data.plans(), 0.95d),
                literature.statusCounts(),
                literature.eventCounts(),
                literature.pendingCount(),
                literature.runningCount(),
                literature.oldestPendingAgeMs(),
                literature.oldestRunningAgeMs(),
                runningPlans,
                slowPlans,
                alerts(data, literature)
        );
    }

    public AlertResponse alerts(Integer requestedWindowMinutes) {
        WindowData data = loadWindow(requestedWindowMinutes);
        List<AlertItem> alertItems = alerts(data, literatureSummary(data));
        String status = alertItems.stream().anyMatch(item -> "CRITICAL".equals(item.severity()))
                ? "CRITICAL"
                : alertItems.stream().anyMatch(item -> "WARN".equals(item.severity())) ? "WARN" : "OK";
        return new AlertResponse(data.now(), data.windowMinutes(), status, alertItems);
    }

    private WindowData loadWindow(Integer requestedWindowMinutes) {
        LocalDateTime now = LocalDateTime.now();
        int windowMinutes = requestedWindowMinutes == null || requestedWindowMinutes <= 0
                ? properties.getDefaultWindowMinutes()
                : Math.min(24 * 60, requestedWindowMinutes);
        LocalDateTime cutoff = now.minusMinutes(windowMinutes);
        Instant cutoffInstant = cutoff.atZone(ZoneId.systemDefault()).toInstant();
        return new WindowData(
                now,
                windowMinutes,
                plans.findByCreatedAtAfterOrderByCreatedAtDesc(cutoff),
                events.findByCreatedAtAfterOrderByCreatedAtDesc(cutoff),
                taskEvents.findByCreatedAtAfterOrderByCreatedAtDesc(cutoffInstant),
                literatureTasks.findByCreatedAtAfterOrderByCreatedAtDesc(cutoffInstant)
        );
    }

    private Map<String, Long> statusCounts(List<AgentPlan> plans) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AgentPlanStatus status : AgentPlanStatus.values()) {
            counts.put(status.name(), 0L);
        }
        for (AgentPlan plan : plans) {
            counts.compute(plan.getStatus(), (ignored, count) -> count == null ? 1L : count + 1L);
        }
        return counts;
    }

    private Map<String, Long> eventCounts(List<AgentPlanEvent> events) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AgentPlanEvent event : events) {
            counts.compute(event.getEventType(), (ignored, count) -> count == null ? 1L : count + 1L);
        }
        return counts;
    }

    private List<AlertItem> alerts(WindowData data, LiteratureLifecycleSummary literature) {
        List<AlertItem> alerts = new ArrayList<>();
        double failureRate = failureRate(data.plans());
        alerts.add(rateAlert(
                "plan_failure_rate",
                failureRate,
                properties.getPlanFailureRateWarning(),
                properties.getPlanFailureRateCritical(),
                "Plan terminal failure rate in the selected window."
        ));
        long maxRunningAgeMs = data.plans().stream()
                .filter(plan -> AgentPlanStatus.RUNNING.name().equals(plan.getStatus()))
                .mapToLong(plan -> runningAgeMs(plan, data.now()))
                .max()
                .orElse(0L);
        alerts.add(durationAlert(
                "running_plan_age",
                maxRunningAgeMs,
                properties.getRunningPlanAgeWarningSeconds() * 1000L,
                properties.getRunningPlanAgeCriticalSeconds() * 1000L,
                "Oldest RUNNING plan age."
        ));
        long p95 = percentileDurationMs(data.plans(), 0.95d);
        alerts.add(durationAlert(
                "plan_p95_duration",
                p95,
                properties.getPlanP95WarningMs(),
                properties.getPlanP95CriticalMs(),
                "P95 duration for terminal plans."
        ));
        long guardrailCount = data.events().stream()
                .filter(event -> GUARDRAIL_EVENTS.contains(event.getEventType()))
                .count();
        alerts.add(countAlert(
                "guardrail_events",
                guardrailCount,
                properties.getGuardrailEventWarning(),
                properties.getGuardrailEventCritical(),
                "Guardrail or verification events in the selected window."
        ));
        alerts.add(countAlert(
                "literature_pending_backlog",
                literature.pendingCount(),
                properties.getLiteraturePendingBacklogWarning(),
                properties.getLiteraturePendingBacklogCritical(),
                "Pending literature search task backlog."
        ));
        alerts.add(durationAlert(
                "literature_running_age",
                literature.oldestRunningAgeMs(),
                properties.getLiteratureRunningAgeWarningSeconds() * 1000L,
                properties.getLiteratureRunningAgeCriticalSeconds() * 1000L,
                "Oldest RUNNING literature search task age."
        ));
        alerts.add(countAlert(
                "literature_timeout_events",
                literature.eventCounts().getOrDefault("TASK_TIMED_OUT", 0L),
                properties.getLiteratureTimeoutEventWarning(),
                properties.getLiteratureTimeoutEventCritical(),
                "Timed out literature search tasks in the selected window."
        ));
        return alerts;
    }

    private LiteratureLifecycleSummary literatureSummary(WindowData data) {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        statusCounts.put("PENDING", 0L);
        statusCounts.put("RUNNING", 0L);
        statusCounts.put("CANCEL_REQUESTED", 0L);
        statusCounts.put("CANCELLING", 0L);
        statusCounts.put("COMPLETED", 0L);
        statusCounts.put("FAILED", 0L);
        statusCounts.put("CANCELLED", 0L);
        for (LiteratureSearchTask task : data.literatureTasks()) {
            statusCounts.compute(task.getStatus(), (ignored, count) -> count == null ? 1L : count + 1L);
        }

        Map<String, Long> eventCounts = new LinkedHashMap<>();
        for (String eventType : LITERATURE_MONITOR_EVENTS) {
            eventCounts.put(eventType, 0L);
        }
        for (AgentTaskEvent event : data.taskEvents()) {
            if (!AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH.equals(event.getTaskType())) {
                continue;
            }
            if (!LITERATURE_MONITOR_EVENTS.contains(event.getEventType())) {
                continue;
            }
            eventCounts.compute(event.getEventType(), (ignored, count) -> count == null ? 1L : count + 1L);
        }

        long pendingCount = statusCounts.getOrDefault("PENDING", 0L);
        long runningCount = statusCounts.getOrDefault("RUNNING", 0L);
        Instant nowInstant = data.now().atZone(ZoneId.systemDefault()).toInstant();
        long oldestPendingAgeMs = data.literatureTasks().stream()
                .filter(task -> "PENDING".equals(task.getStatus()))
                .map(LiteratureSearchTask::getCreatedAt)
                .filter(createdAt -> createdAt != null)
                .mapToLong(createdAt -> Duration.between(createdAt, nowInstant).toMillis())
                .max()
                .orElse(0L);
        long oldestRunningAgeMs = data.literatureTasks().stream()
                .filter(task -> "RUNNING".equals(task.getStatus()))
                .map(task -> task.getStartedAt() == null ? task.getCreatedAt() : task.getStartedAt())
                .filter(startedAt -> startedAt != null)
                .mapToLong(startedAt -> Duration.between(startedAt, nowInstant).toMillis())
                .max()
                .orElse(0L);
        return new LiteratureLifecycleSummary(
                statusCounts,
                eventCounts,
                pendingCount,
                runningCount,
                oldestPendingAgeMs,
                oldestRunningAgeMs
        );
    }

    private AlertItem rateAlert(String id, double value, double warning, double critical, String message) {
        String severity = value >= critical ? "CRITICAL" : value >= warning ? "WARN" : "OK";
        return new AlertItem(id, severity, round(value), warning, critical, message);
    }

    private AlertItem durationAlert(String id, long valueMs, long warningMs, long criticalMs, String message) {
        String severity = valueMs >= criticalMs ? "CRITICAL" : valueMs >= warningMs ? "WARN" : "OK";
        return new AlertItem(id, severity, valueMs, warningMs, criticalMs, message);
    }

    private AlertItem countAlert(String id, long value, long warning, long critical, String message) {
        String severity = value >= critical ? "CRITICAL" : value >= warning ? "WARN" : "OK";
        return new AlertItem(id, severity, value, warning, critical, message);
    }

    private long terminalCount(List<AgentPlan> plans) {
        return plans.stream().filter(AgentPlan::terminal).count();
    }

    private double failureRate(List<AgentPlan> plans) {
        long terminal = terminalCount(plans);
        if (terminal == 0L) {
            return 0.0d;
        }
        long failed = plans.stream()
                .filter(plan -> AgentPlanStatus.FAILED.name().equals(plan.getStatus()))
                .count();
        return round((double) failed / terminal);
    }

    private long averageDurationMs(List<AgentPlan> plans) {
        return Math.round(plans.stream()
                .map(this::durationMs)
                .filter(value -> value != null && value >= 0)
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0d));
    }

    private long percentileDurationMs(List<AgentPlan> plans, double percentile) {
        List<Long> durations = plans.stream()
                .map(this::durationMs)
                .filter(value -> value != null && value >= 0)
                .sorted()
                .toList();
        if (durations.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile * durations.size()) - 1;
        return durations.get(Math.max(0, Math.min(index, durations.size() - 1)));
    }

    private Long durationMs(AgentPlan plan) {
        if (plan.getFinishedAt() == null) {
            return null;
        }
        LocalDateTime start = plan.getStartedAt() == null ? plan.getCreatedAt() : plan.getStartedAt();
        return Duration.between(start, plan.getFinishedAt()).toMillis();
    }

    private long runningAgeMs(AgentPlan plan, LocalDateTime now) {
        LocalDateTime start = plan.getStartedAt() == null ? plan.getCreatedAt() : plan.getStartedAt();
        return Math.max(0L, Duration.between(start, now).toMillis());
    }

    private String safeError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "";
        }
        String normalized = errorMessage.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 237) + "...";
    }

    private double round(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.4f", value));
    }

    private record WindowData(LocalDateTime now,
                              int windowMinutes,
                              List<AgentPlan> plans,
                              List<AgentPlanEvent> events,
                              List<AgentTaskEvent> taskEvents,
                              List<LiteratureSearchTask> literatureTasks) {
    }

    public record DashboardResponse(LocalDateTime generatedAt,
                                    int windowMinutes,
                                    int planCount,
                                    Map<String, Long> planStatusCounts,
                                    Map<String, Long> eventTypeCounts,
                                    long terminalPlanCount,
                                    double planFailureRate,
                                    long averagePlanDurationMs,
                                    long p95PlanDurationMs,
                                    Map<String, Long> literatureTaskStatusCounts,
                                    Map<String, Long> literatureLifecycleEventCounts,
                                    long literaturePendingCount,
                                    long literatureRunningCount,
                                    long literatureOldestPendingAgeMs,
                                    long literatureOldestRunningAgeMs,
                                    List<PlanSummary> runningPlans,
                                    List<PlanSummary> slowPlans,
                                    List<AlertItem> alerts) {
    }

    public record AlertResponse(LocalDateTime generatedAt,
                                int windowMinutes,
                                String status,
                                List<AlertItem> alerts) {
    }

    public record AlertItem(String id,
                            String severity,
                            Number value,
                            Number warningThreshold,
                            Number criticalThreshold,
                            String message) {
    }

    public record PlanSummary(Long planId,
                              String status,
                              Long durationMs,
                              LocalDateTime startedAt,
                              LocalDateTime finishedAt,
                              String error) {
    }

    private record LiteratureLifecycleSummary(Map<String, Long> statusCounts,
                                              Map<String, Long> eventCounts,
                                              long pendingCount,
                                              long runningCount,
                                              long oldestPendingAgeMs,
                                              long oldestRunningAgeMs) {
    }
}
