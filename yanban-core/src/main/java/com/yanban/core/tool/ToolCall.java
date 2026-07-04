package com.yanban.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(
        String id,
        String name,
        JsonNode arguments
) {
    public ToolCall {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool call name must not be blank");
        }
    }
}
