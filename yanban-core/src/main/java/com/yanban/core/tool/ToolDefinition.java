package com.yanban.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.core.model.ToolSpec;

public record ToolDefinition(
        String name,
        String description,
        JsonNode parameters
) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool description must not be blank");
        }
        if (parameters == null) {
            throw new IllegalArgumentException("tool parameters JSON schema must not be null");
        }
    }

    public ToolSpec toModelToolSpec() {
        return ToolSpec.function(name, description, parameters);
    }
}
