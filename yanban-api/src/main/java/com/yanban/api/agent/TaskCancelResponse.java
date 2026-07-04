package com.yanban.api.agent;

public record TaskCancelResponse(
        String taskType,
        Long taskId,
        boolean cancelAccepted,
        boolean idempotent,
        String beforeStatus,
        String afterStatus,
        String currentStage,
        String message
) {
}
