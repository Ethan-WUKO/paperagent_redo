package com.yanban.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDescriptor(
        String name,
        String description,
        JsonNode inputSchema
) {
}
