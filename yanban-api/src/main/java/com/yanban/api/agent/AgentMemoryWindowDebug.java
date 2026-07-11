package com.yanban.api.agent;

import java.util.List;

public record AgentMemoryWindowDebug(
        AgentMemoryMode mode,
        List<String> entries
) {
    public AgentMemoryWindowDebug {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
