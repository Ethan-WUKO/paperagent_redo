package com.yanban.core.agent;

public record AgentTaskEventCreateRequest(
        String taskType,
        Long taskId,
        Long userId,
        String eventType,
        String stage,
        String status,
        String message,
        String payloadJson
) {
}
