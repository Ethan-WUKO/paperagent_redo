package com.yanban.api.agent;

import com.yanban.core.agent.AgentPlanStepStatus;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** Adapter boundary between the Coordinator and the existing persisted Plan scheduler. */
@Component
public class PlanRuntimeAdapter implements RuntimeAdapter {

    private static final int MAX_CHAT_CONTENT = 12_000;
    private static final int MAX_STEP_RESULT = 3_000;

    private final PlanAgentService planAgentService;

    public PlanRuntimeAdapter(@Lazy PlanAgentService planAgentService) {
        this.planAgentService = planAgentService;
    }

    @Override
    public boolean supports(AgentStrategy strategy) {
        return strategy == AgentStrategy.PLAN_EXECUTE;
    }

    @Override
    public AgentRuntimeResult run(AgentRuntimeRequest request) {
        if (request.planId() == null) {
            Long createdPlanId = null;
            try {
                AgentPlanResponse created = planAgentService.createPlanWithinAdapter(request);
                createdPlanId = created.id();
                if (isServerAutoProjectPlan(request)) {
                    return execute(request, createdPlanId, false);
                }
                String content = "Plan " + created.id() + " created.";
                return new AgentRuntimeResult(true, content, List.of(ChatMessage.assistant(content)), 0,
                        null, List.of(), List.of(), null, null, null)
                        .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.COMPLETED, "PLAN_CREATED", false, null)
                        .withPlanId(created.id());
            } catch (RuntimeException ex) {
                String error = ex instanceof ResponseStatusException statusException
                        && StringUtils.hasText(statusException.getReason())
                        ? statusException.getReason()
                        : (StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName());
                return new AgentRuntimeResult(false, null, List.of(), 0, error, List.of(), List.of(),
                        null, null, null)
                        .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.RUNTIME_FAILED, "FAILURE", false, null)
                        .withPlanId(createdPlanId);
            }
        }
        return execute(request, request.planId(), true);
    }

    private AgentRuntimeResult execute(AgentRuntimeRequest request, Long planId,
                                       boolean persistConversationSummary) {
        PlanAgentService.PlanExecutionResult execution = planAgentService.executePlanResultWithinAdapter(
                request.userId(), planId, request.traceId(), persistConversationSummary);
        AgentPlanResponse plan = execution.plan();
        PlanTerminal terminal = classify(plan, execution.stopSignal());
        String content = buildExecutionContent(plan);
        List<AgentPlanStepResponse> planSteps = plan.steps() == null ? List.of() : plan.steps();
        return new AgentRuntimeResult(
                terminal.success(), content, List.of(ChatMessage.assistant(content)), planSteps.size(),
                terminal.success() ? null : plan.errorMessage(), List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, terminal.stopReason(), terminal.outcome(),
                        terminal.degraded(), terminal.degraded() ? AgentStrategy.PLAN_EXECUTE : null)
                .withRuntimeStopSignal(terminal.stopSignal())
                .withPlanId(plan.id())
                .withTrustedEvidenceLedger(execution.evidenceLedger());
    }

    static boolean isServerAutoProjectPlan(AgentRuntimeRequest request) {
        if (request == null || request.strategy() != AgentStrategy.PLAN_EXECUTE
                || request.projectContext() == null || request.orchestrationRequirements() == null) {
            return false;
        }
        AgentOrchestrationRequirements audit = request.orchestrationRequirements();
        return audit.selectionOrigin() == AgentStrategySelectionOrigin.SERVER_AUTO
                && audit.reasonCodes().contains(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN);
    }

    private String buildExecutionContent(AgentPlanResponse plan) {
        StringBuilder content = new StringBuilder("Plan ").append(plan.id())
                .append(" finished with status ").append(plan.status()).append(".");
        List<AgentPlanStepResponse> steps = plan.steps() == null ? List.of() : plan.steps();
        for (AgentPlanStepResponse step : steps) {
            String detail = StringUtils.hasText(step.result()) ? step.result() : step.errorMessage();
            if (!StringUtils.hasText(detail)) continue;
            String bounded = detail.length() <= MAX_STEP_RESULT
                    ? detail : detail.substring(0, MAX_STEP_RESULT) + "...";
            content.append("\n\n").append(StringUtils.hasText(step.title()) ? step.title() : step.stepKey())
                    .append(" [").append(step.status()).append("]:\n").append(bounded);
            if (content.length() >= MAX_CHAT_CONTENT) {
                return content.substring(0, MAX_CHAT_CONTENT) + "...";
            }
        }
        return content.toString();
    }

    static PlanTerminal classify(AgentPlanResponse plan, AgentRuntimeStopSignal stopSignal) {
        List<AgentPlanStepResponse> steps = plan.steps() == null ? List.of() : plan.steps();
        boolean degraded = steps.stream().anyMatch(step -> AgentPlanStepStatus.DEGRADED.name().equals(step.status())
                || AgentPlanStepStatus.SKIPPED.name().equals(step.status()));
        if (stopSignal == AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED
                || stopSignal == AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED) {
            return new PlanTerminal(false, true, AgentStopReason.MAX_STEPS_BUDGET_EXHAUSTED,
                    "BUDGET_STOP", stopSignal);
        }
        if ("COMPLETED".equals(plan.status())) {
            return degraded
                    ? new PlanTerminal(false, true, AgentStopReason.PLAN_PARTIAL,
                    "PARTIAL", AgentRuntimeStopSignal.NONE)
                    : new PlanTerminal(true, false, AgentStopReason.COMPLETED,
                    "SUCCESS", AgentRuntimeStopSignal.NONE);
        }
        return switch (plan.status()) {
            case "PAUSED" -> new PlanTerminal(false, false, AgentStopReason.PAUSED,
                    "PAUSED", AgentRuntimeStopSignal.NONE);
            case "REVIEWING", "RUNNING" -> new PlanTerminal(false, false, AgentStopReason.WAITING_FOR_USER,
                    "WAITING", AgentRuntimeStopSignal.NONE);
            default -> new PlanTerminal(false, false, AgentStopReason.RUNTIME_FAILED,
                    "FAILURE", AgentRuntimeStopSignal.NONE);
        };
    }

    static PlanTerminal classify(AgentPlanResponse plan) {
        return classify(plan, AgentRuntimeStopSignal.NONE);
    }

    record PlanTerminal(boolean success, boolean degraded, AgentStopReason stopReason,
                        String outcome, AgentRuntimeStopSignal stopSignal) {
    }
}
