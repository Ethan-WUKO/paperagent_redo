package com.yanban.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface McpStdioClient extends AutoCloseable {
    void initialize();

    List<McpToolDescriptor> listTools();

    JsonNode callTool(String toolName, JsonNode arguments);

    @Override
    void close();
}
