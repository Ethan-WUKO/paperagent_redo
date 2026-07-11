package com.yanban.api.agent;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The sole CHAT-to-runtime facade.  It makes the deterministic strategy decision only
 * after the model endpoint, budget and resolved tool policy have been supplied.
 */
@Service
public class AgentRuntimeCoordinator {

    private final AgentRuntimeService runtimeService;
    private final AgentStrategySelector strategySelector;

    public AgentRuntimeCoordinator(AgentRuntimeService runtimeService,
                                   AgentStrategySelector strategySelector) {
        this.runtimeService = runtimeService;
        this.strategySelector = strategySelector;
    }

    public AgentCoordinationResult coordinate(AgentCoordinationRequest request) {
        AgentRuntimeRequest resolved = request.runtimeRequest();
        boolean explicitPlanRequest = request.explicitPlanRequest();
        if ((request.capability() == AgentRequestCapability.PROJECT_READ
                || request.capability() == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ)
                && (resolved.projectContext() == null
                || !resolved.userId().equals(resolved.projectContext().userId()))) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.DIRECT, false, false, null, "missing_trusted_project_context");
            return new AgentCoordinationResult(decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Project execution requires authenticated project context."));
        }
        if (request.capability() != AgentRequestCapability.PROJECT_READ
                && request.capability() != AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ && resolved.projectContext() != null) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.DIRECT, false, false, null, "project_context_capability_mismatch");
            return new AgentCoordinationResult(decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Project context is invalid for this request capability."));
        }
        if (request.capability() == AgentRequestCapability.LEGACY_PLAN_REFLECT
                && !strategySelector.isPlanReflectIntent(resolved.userMessage())) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.DIRECT, true, false, null, "invalid_legacy_plan_reflect_request");
            return new AgentCoordinationResult(decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Legacy plan reflection requires '/plan reflect <goal>'."));
        }
        if ((request.capability() == AgentRequestCapability.TRUSTED_PLAN_API
                || request.capability() == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ)
                && request.planOperation() == PlanApiOperation.EXECUTE && request.planId() == null) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.PLAN_EXECUTE, true, false, null, "missing_trusted_plan_id");
            return new AgentCoordinationResult(decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Trusted Plan API execution requires a persisted plan id."));
        }
        if ((request.capability() == AgentRequestCapability.TRUSTED_PLAN_API
                || request.capability() == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ)
                && request.planOperation() == PlanApiOperation.CREATE && request.planId() != null) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.PLAN_EXECUTE, true, false, null, "unexpected_plan_id_for_create");
            return new AgentCoordinationResult(decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Trusted Plan API creation must not supply a plan id."));
        }
        if ((request.capability() == AgentRequestCapability.TRUSTED_PLAN_API
                || request.capability() == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ)
                && resolved.planId() != null && !resolved.planId().equals(request.planId())) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.PLAN_EXECUTE, true, false, null, "conflicting_trusted_plan_id");
            return new AgentCoordinationResult(decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Trusted Plan API request contains conflicting plan ids."));
        }
        if (!StringUtils.hasText(resolved.provider()) || !StringUtils.hasText(resolved.model())
                || resolved.toolPolicy() == null || resolved.maxSteps() <= 0) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.DIRECT, explicitPlanRequest, false, null, "unresolved_runtime_prerequisite");
            return new AgentCoordinationResult(decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Runtime request is missing a resolved endpoint, budget, or tool policy."));
        }

        AgentStrategy selected = strategySelector.select(request);
        AgentCoordinationDecision decision = new AgentCoordinationDecision(
                selected, explicitPlanRequest, false, null,
                switch (request.capability()) {
                    case TRUSTED_PLAN_API -> "trusted_plan_api";
                    case TRUSTED_PROJECT_PLAN_READ -> "trusted_project_plan_read";
                    case LEGACY_PLAN_REFLECT -> "explicit_legacy_plan_reflect";
                    case PROJECT_READ -> "trusted_project_read";
                    case CHAT -> "chat_tool_policy";
                });
        try {
            // planId is canonical on the coordination envelope and is copied only here,
            // after the trusted capability and conflict checks have completed.
            AgentRuntimeResult result = runtimeService.run(resolved.withStrategy(selected).withPlanId(request.planId()));
            AgentStopReason stopReason = result.stopReason() == null ? stopReason(result) : result.stopReason();
            return new AgentCoordinationResult(decision, result.withCoordination(
                    selected, stopReason, result.outcome() == null ? outcome(result) : result.outcome(),
                    result.degraded(), result.degradedFrom()));
        } catch (NoRuntimeAdapterException ex) {
            return new AgentCoordinationResult(decision, failed(decision, AgentStopReason.NO_RUNTIME_ADAPTER, ex.getMessage()));
        } catch (RuntimeException ex) {
            return new AgentCoordinationResult(decision, failed(decision, AgentStopReason.RUNTIME_EXCEPTION,
                    StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    private AgentStopReason stopReason(AgentRuntimeResult result) {
        return switch (result.runtimeStopSignal()) {
            case TOOL_CALL_BUDGET_EXHAUSTED -> AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED;
            case MAX_STEPS_BUDGET_EXHAUSTED -> AgentStopReason.MAX_STEPS_BUDGET_EXHAUSTED;
            case NONE -> result.success() ? AgentStopReason.COMPLETED : AgentStopReason.RUNTIME_FAILED;
        };
    }

    private String outcome(AgentRuntimeResult result) {
        return result.runtimeStopSignal() == AgentRuntimeStopSignal.NONE
                ? (result.success() ? "SUCCESS" : "FAILURE")
                : "BUDGET_STOP";
    }

    private AgentRuntimeResult failed(AgentCoordinationDecision decision, AgentStopReason stopReason, String message) {
        return new AgentRuntimeResult(false, null, List.of(), 0, message, List.of(), List.of(message), null, null, null)
                .withCoordination(decision.selectedStrategy(), stopReason, "FAILURE", decision.degraded(), decision.degradedFrom());
    }
}
