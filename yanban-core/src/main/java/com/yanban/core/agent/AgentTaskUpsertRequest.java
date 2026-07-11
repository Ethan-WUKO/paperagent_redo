package com.yanban.core.agent;

import java.time.Instant;

public record AgentTaskUpsertRequest(
        Long userId,
        Long projectId,
        String taskType,
        String source,
        Long sourceId,
        String status,
        String strategy,
        String clientRequestId,
        String title,
        String inputSummary,
        Integer progressPercent,
        String currentStage,
        String errorCode,
        String errorMessage,
        String cancellationReason,
        Integer retryCount,
        Integer maxRetries,
        Instant startedAt,
        Instant finishedAt
) {
}
