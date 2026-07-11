package com.yanban.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import com.yanban.mcp.McpStdioClient;
import java.util.Iterator;
import java.util.List;

public class McpProxyToolExecutor implements ToolExecutor {

    private final McpServerKind kind;
    private final String remoteToolName;
    private final ToolDefinition definition;
    private final McpClientFactory clientFactory;
    private final UserSettingsService userSettingsService;
    private final FilesystemPathGuard pathGuard;
    private final ObjectMapper objectMapper;

    public McpProxyToolExecutor(McpServerKind kind,
                                String prefixedToolName,
                                String remoteToolName,
                                String description,
                                JsonNode inputSchema,
                                McpClientFactory clientFactory,
                                UserSettingsService userSettingsService,
                                FilesystemPathGuard pathGuard,
                                ObjectMapper objectMapper) {
        this.kind = kind;
        this.remoteToolName = remoteToolName;
        this.definition = new ToolDefinition(prefixedToolName, description, inputSchema == null || inputSchema.isMissingNode() ? objectMapper.createObjectNode().put("type", "object") : inputSchema);
        this.clientFactory = clientFactory;
        this.userSettingsService = userSettingsService;
        this.pathGuard = pathGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Long userId = ToolExecutionContext.getCurrentUserId();
        if (userId == null) {
            return ToolResult.failure(call.id(), definition.name(), "缺少当前用户上下文，无法执行 MCP 工具");
        }
        try {
            JsonNode arguments = call.arguments() == null ? objectMapper.createObjectNode() : call.arguments();
            if (kind == McpServerKind.FILESYSTEM) {
                SysUserSettings settings = userSettingsService.getOrCreate(userId);
                validateFilesystemArgs(arguments, userSettingsService.parseFilesystemRoots(settings));
            }
            try (McpStdioClient client = clientFactory.createForUser(kind, userId)) {
                JsonNode result = client.callTool(remoteToolName, arguments);
                return ToolResult.success(call.id(), definition.name(), result);
            }
        } catch (Exception ex) {
            return ToolResult.failure(call.id(), definition.name(), ex.getMessage());
        }
    }

    private void validateFilesystemArgs(JsonNode arguments, List<String> roots) {
        Iterator<String> fields = arguments.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            JsonNode value = arguments.get(field);
            if (isPathField(field) && value.isTextual()) {
                pathGuard.validateAllowed(value.asText(), roots);
            }
            if (isPathField(field) && value.isArray()) {
                for (JsonNode item : value) {
                    if (item.isTextual()) {
                        pathGuard.validateAllowed(item.asText(), roots);
                    }
                }
            }
        }
    }

    private boolean isPathField(String field) {
        String normalized = field.toLowerCase();
        return normalized.equals("path") || normalized.equals("paths") || normalized.equals("directory") || normalized.equals("root");
    }
}
