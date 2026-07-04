package com.yanban.api.agent;

import com.yanban.core.model.ChatMessage;
import java.util.List;

public record AgentContextPackage(
        List<ChatMessage> messages,
        List<AgentContextSection> sections,
        List<AgentContextDroppedItem> droppedItems,
        int rawMessageCount,
        int normalizedMessageCount,
        int estimatedCharacters
) {
    public AgentContextPackage {
        messages = messages == null ? List.of() : List.copyOf(messages);
        sections = sections == null ? List.of() : List.copyOf(sections);
        droppedItems = droppedItems == null ? List.of() : List.copyOf(droppedItems);
    }
}
