package com.yanban.core.tool;

public interface ToolExecutor {
    ToolDefinition definition();

    ToolResult execute(ToolCall call);
}
