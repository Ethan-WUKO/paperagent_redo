package com.yanban.api.agent;

import java.time.Instant;
import java.util.List;

public record AgentContextSnapshotResponse(
        Long id,
        Long turnId,
        Long sessionId,
        Long userId,
        String traceId,
        List<AgentContextSection> sections,
        List<AgentContextDroppedItem> droppedItems,
        int rawMessageCount,
        int normalizedMessageCount,
        int contextMessageCount,
        int estimatedCharacters,
        Instant createdAt
) {
}
