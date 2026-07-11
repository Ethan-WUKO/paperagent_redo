package com.yanban.core.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolSpec(
        String type,
        FunctionSpec function
) {
    public static ToolSpec function(String name, String description, JsonNode parameters) {
        return new ToolSpec("function", new FunctionSpec(name, description, parameters));
    }

    public record FunctionSpec(String name, String description, JsonNode parameters) {
    }
}
