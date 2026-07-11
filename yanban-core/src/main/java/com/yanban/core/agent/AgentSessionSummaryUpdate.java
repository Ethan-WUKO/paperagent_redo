package com.yanban.core.agent;

public record AgentSessionSummaryUpdate(
        Long sessionId,
        Long userId,
        String summaryText,
        Long coveredMessageId,
        Integer messageCount,
        String modelProviderSnapshot,
        String modelSnapshot
) {
    public AgentSessionSummaryUpdate {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (summaryText == null || summaryText.isBlank()) {
            throw new IllegalArgumentException("summaryText must not be blank");
        }
    }
}
