package com.yanban.api.agent;

import org.springframework.util.StringUtils;

public record AgentLongTermMemoryContext(
        String content,
        int hitCount,
        int candidateCount,
        int omittedCount,
        String note
) {
    public static AgentLongTermMemoryContext empty() {
        return new AgentLongTermMemoryContext(null, 0, 0, 0, "No relevant long-term memory was injected.");
    }

    public boolean hasContent() {
        return StringUtils.hasText(content);
    }
}
