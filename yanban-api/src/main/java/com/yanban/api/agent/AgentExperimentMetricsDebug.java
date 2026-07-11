package com.yanban.api.agent;

public record AgentExperimentMetricsDebug(
        String clientRequestId,
        Long sessionId,
        Long latencyMs,
        Integer retrievedChunkCount,
        Integer memoryWindowSize,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer toolCallCount,
        Integer steps,
        Long evalRecordId
) {
}
