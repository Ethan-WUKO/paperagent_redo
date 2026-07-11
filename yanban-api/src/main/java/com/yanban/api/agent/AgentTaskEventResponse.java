package com.yanban.api.agent;

import com.yanban.core.agent.AgentTaskEvent;
import java.time.Instant;

public record AgentTaskEventResponse(
        Long id,
        String taskType,
        Long taskId,
        Long userId,
        String eventType,
        String stage,
        String status,
        String message,
        String payloadJson,
        Instant createdAt
) {
    public static AgentTaskEventResponse from(AgentTaskEvent event) {
        return new AgentTaskEventResponse(
                event.getId(),
                event.getTaskType(),
                event.getTaskId(),
                event.getUserId(),
                event.getEventType(),
                event.getStage(),
                event.getStatus(),
                event.getMessage(),
                event.getPayloadJson(),
                event.getCreatedAt()
        );
    }
}
