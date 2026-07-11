package com.yanban.api.agent;

public record TaskCancelRequest(
        String taskType,
        String cancelReason
) {
}
