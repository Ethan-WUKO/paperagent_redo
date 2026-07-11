package com.yanban.api.agent;

import com.yanban.core.agent.AgentMessage;
import java.time.Instant;

public record AgentMessageResponse(
        Long id,
        Long sessionId,
        Long userId,
        String role,
        String content,
        String toolCallsJson,
        String toolCallId,
        Long paperTaskId,
        Instant createdAt
) {
    public static AgentMessageResponse from(AgentMessage message) {
        return new AgentMessageResponse(
                message.getId(),
                message.getSessionId(),
                message.getUserId(),
                message.getRole(),
                message.getContent(),
                message.getToolCallsJson(),
                message.getToolCallId(),
                message.getPaperTaskId(),
                message.getCreatedAt()
        );
    }
}
