package com.yanban.api.agent;

import java.time.Instant;

public record TaskStatusResponse(
        String taskType,
        Long taskId,
        String status,
        String currentStage,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        Integer progressPercent,
        String errorMessage,
        String cancellationReason,
        boolean partialResultAvailable,
        int completedArtifactCount,
        int partialArtifactCount,
        boolean terminal,
        boolean cancellable
) {
}
