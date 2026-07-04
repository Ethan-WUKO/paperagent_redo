package com.yanban.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record McpServerProcessConfig(
        List<String> command,
        List<String> allowedCommands,
        Map<String, String> environment,
        Duration startupTimeout,
        Duration requestTimeout
) {
}
