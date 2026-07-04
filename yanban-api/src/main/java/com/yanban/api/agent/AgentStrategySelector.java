package com.yanban.api.agent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgentStrategySelector {

    public AgentStrategy select(String userMessage, AgentToolPolicyEngine.Decision toolPolicy) {
        if (isPlanReflectIntent(userMessage)) {
            return AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION;
        }
        if (toolPolicy == null || toolPolicy.allowedTools() == null || toolPolicy.allowedTools().isEmpty()) {
            return AgentStrategy.DIRECT;
        }
        return AgentStrategy.SINGLE_STEP_REACT;
    }

    public boolean isPlanReflectIntent(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return false;
        }
        String normalized = userMessage.trim();
        return normalized.regionMatches(true, 0, "/plan reflect", 0, "/plan reflect".length());
    }
}
