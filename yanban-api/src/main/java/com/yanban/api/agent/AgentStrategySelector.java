package com.yanban.api.agent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgentStrategySelector {

    public AgentStrategy select(String userMessage, AgentToolPolicyEngine.Decision toolPolicy) {
        return select(userMessage, toolPolicy == null ? null : toolPolicy.resolved(), isPlanReflectIntent(userMessage));
    }

    public AgentStrategy select(String userMessage, ResolvedToolPolicy toolPolicy, boolean explicitPlanRequest) {
        if (explicitPlanRequest && isPlanReflectIntent(userMessage)) {
            return AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION;
        }
        if (toolPolicy == null || toolPolicy.allowedTools().isEmpty()) {
            return AgentStrategy.DIRECT;
        }
        return AgentStrategy.SINGLE_STEP_REACT;
    }

    public AgentStrategy select(AgentCoordinationRequest request) {
        return switch (request.capability()) {
            case TRUSTED_PLAN_API -> AgentStrategy.PLAN_EXECUTE;
            case TRUSTED_PROJECT_PLAN_READ -> AgentStrategy.PLAN_EXECUTE;
            case LEGACY_PLAN_REFLECT -> AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION;
            case PROJECT_READ -> select(request.runtimeRequest().userMessage(), request.runtimeRequest().toolPolicy(), false);
            case CHAT -> select(request.runtimeRequest().userMessage(), request.runtimeRequest().toolPolicy(), false);
        };
    }

    public boolean isPlanReflectIntent(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return false;
        }
        String normalized = userMessage.trim();
        String command = "/plan reflect";
        return normalized.equalsIgnoreCase(command)
                || (normalized.length() > command.length()
                && normalized.regionMatches(true, 0, command, 0, command.length())
                && Character.isWhitespace(normalized.charAt(command.length())));
    }
}
