package com.yanban.api.agent;

import com.yanban.core.agent.AgentSession;
import java.time.Instant;

public record AgentSessionResponse(
        Long id,
        Long userId,
        String title,
        String modelProvider,
        String model,
        Integer maxSteps,
        Boolean ragDisabled,
        Instant createdAt,
        Instant updatedAt
) {
    public static AgentSessionResponse from(AgentSession session) {
        return new AgentSessionResponse(
                session.getId(),
                session.getUserId(),
                session.getTitle(),
                session.getModelProviderSnapshot(),
                session.getModelSnapshot(),
                session.getMaxSteps(),
                session.getRagDisabled(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }
}
