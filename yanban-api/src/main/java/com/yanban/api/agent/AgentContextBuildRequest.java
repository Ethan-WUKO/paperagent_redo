package com.yanban.api.agent;

public record AgentContextBuildRequest(
        Long sessionId,
        Long userId,
        String providerKey,
        String modelName,
        String sessionSummary,
        String ragContext,
        String toolTraceContext,
        Integer maxRecentMessages,
        Integer maxContextCharacters
) {
    public AgentContextBuildRequest(Long sessionId,
                                    Long userId,
                                    String providerKey,
                                    String modelName,
                                    String sessionSummary,
                                    Integer maxRecentMessages,
                                    Integer maxContextCharacters) {
        this(sessionId, userId, providerKey, modelName, sessionSummary, null, null, maxRecentMessages, maxContextCharacters);
    }

    public AgentContextBuildRequest {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
    }
}
