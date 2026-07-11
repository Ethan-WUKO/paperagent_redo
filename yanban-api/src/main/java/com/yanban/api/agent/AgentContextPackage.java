package com.yanban.api.agent;

import com.yanban.core.model.ChatMessage;
import java.util.List;

public record AgentContextPackage(
        List<ChatMessage> messages,
        List<AgentContextSection> sections,
        List<AgentContextDroppedItem> droppedItems,
        int rawMessageCount,
        int normalizedMessageCount,
        int estimatedCharacters,
        EvidenceLedger evidenceLedger
) {
    public AgentContextPackage(List<ChatMessage> messages,
                               List<AgentContextSection> sections,
                               List<AgentContextDroppedItem> droppedItems,
                               int rawMessageCount,
                               int normalizedMessageCount,
                               int estimatedCharacters) {
        this(messages, sections, droppedItems, rawMessageCount, normalizedMessageCount, estimatedCharacters, EvidenceLedger.empty());
    }

    public AgentContextPackage {
        messages = messages == null ? List.of() : List.copyOf(messages);
        sections = sections == null ? List.of() : List.copyOf(sections);
        droppedItems = droppedItems == null ? List.of() : List.copyOf(droppedItems);
        evidenceLedger = evidenceLedger == null ? EvidenceLedger.empty() : evidenceLedger;
    }
}
