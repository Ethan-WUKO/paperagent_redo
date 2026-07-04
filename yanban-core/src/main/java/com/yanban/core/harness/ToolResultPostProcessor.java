package com.yanban.core.harness;

import com.yanban.core.tool.ToolResult;

public interface ToolResultPostProcessor {
    ToolResult process(ToolResult result, HarnessRequest request);
}
