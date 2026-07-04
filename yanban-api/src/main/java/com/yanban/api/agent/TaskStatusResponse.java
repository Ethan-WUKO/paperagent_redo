package com.yanban.api.agent;

import java.time.Instant;

public record TaskStatusResponse(
        String taskType,
        Long taskId,
        String status,
        String currentStage,
        Instant createdAt,
        Instant updatedAt,
        boolean terminal,
        boolean cancellable
) {
}
