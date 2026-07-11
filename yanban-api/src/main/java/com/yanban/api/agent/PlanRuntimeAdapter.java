package com.yanban.api.agent;

import com.yanban.core.agent.AgentPlanStepStatus;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Adapter boundary between the Coordinator and the existing persisted Plan scheduler. */
@Component
public class PlanRuntimeAdapter implements RuntimeAdapter {

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
            try {
                AgentPlanResponse created = planAgentService.createPlanWithinAdapter(request);
                String content = "Plan " + created.id() + " created.";
                return new AgentRuntimeResult(true, content, List.of(ChatMessage.assistant(content)), 0,
                        null, List.of(), List.of(), null, null, null)
                        .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.COMPLETED, "PLAN_CREATED", false, null)
                        .withPlanId(created.id());
            } catch (RuntimeException ex) {
                return new AgentRuntimeResult(false, null, List.of(), 0, ex.getMessage(), List.of(), List.of(),
                        null, null, null)
                        .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.RUNTIME_FAILED, "FAILURE", false, null);
            }
        }
        PlanAgentService.PlanExecutionResult execution = planAgentService.executePlanResultWithinAdapter(
                request.userId(), request.planId(), request.traceId(), true);
        AgentPlanResponse plan = execution.plan();
        PlanTerminal terminal = classify(plan, execution.stopSignal());
        String content = "Plan " + plan.id() + " finished with status " + plan.status() + ".";
        return new AgentRuntimeResult(
                terminal.success(), content, List.of(ChatMessage.assistant(content)), plan.steps().size(),
                terminal.success() ? null : plan.errorMessage(), List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, terminal.stopReason(), terminal.outcome(),
                        terminal.degraded(), terminal.degraded() ? AgentStrategy.PLAN_EXECUTE : null)
                .withRuntimeStopSignal(terminal.stopSignal())
                .withTrustedEvidenceLedger(execution.evidenceLedger());
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
