package com.yanban.api.agent;

public record AgentContextDroppedItem(
        String type,
        int count,
        String reason
) {
}
