package com.yanban.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolResult(
        String toolCallId,
        String toolName,
        boolean success,
        JsonNode output,
        String errorMessage
) {
    public static ToolResult success(String toolCallId, String toolName, JsonNode output) {
        return new ToolResult(toolCallId, toolName, true, output, null);
    }

    public static ToolResult failure(String toolCallId, String toolName, String errorMessage) {
        return new ToolResult(toolCallId, toolName, false, null, errorMessage);
    }
}
