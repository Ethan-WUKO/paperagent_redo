package com.yanban.api.agent;

public record AgentContextSection(
        String type,
        int itemCount,
        int estimatedCharacters,
        String note
) {
}
