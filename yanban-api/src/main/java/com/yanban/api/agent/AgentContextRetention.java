package com.yanban.api.agent;

/**
 * Task state which must survive conversation-window trimming.
 */
public record AgentContextRetention(
        String userConstraints,
        String confirmationDecision,
        String projectId,
        String unfinishedTaskSummary
) {
    public boolean hasContent() {
        return hasText(userConstraints) || hasText(confirmationDecision)
                || hasText(projectId) || hasText(unfinishedTaskSummary);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
