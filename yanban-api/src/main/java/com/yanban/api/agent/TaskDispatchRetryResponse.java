package com.yanban.api.agent;

public record TaskDispatchRetryResponse(
        String taskType,
        Long taskId,
        boolean retryAccepted,
        boolean idempotent,
        String beforeStatus,
        String afterStatus,
        String currentStage,
        String message
) {
}
