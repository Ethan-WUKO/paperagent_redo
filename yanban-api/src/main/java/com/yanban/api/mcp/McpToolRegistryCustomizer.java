package com.yanban.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolRegistryCustomizer;
import com.yanban.mcp.McpStdioClient;
import com.yanban.mcp.McpToolDescriptor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class McpToolRegistryCustomizer implements ToolRegistryCustomizer {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistryCustomizer.class);

    private final McpProperties properties;
    private final McpClientFactory clientFactory;
    private final UserSettingsService userSettingsService;
    private final FilesystemPathGuard pathGuard;
    private final ObjectMapper objectMapper;

    public McpToolRegistryCustomizer(McpProperties properties,
                                     McpClientFactory clientFactory,
                                     UserSettingsService userSettingsService,
                                     FilesystemPathGuard pathGuard,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.userSettingsService = userSettingsService;
        this.pathGuard = pathGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    public void customize(ToolRegistry registry) {
        registerServerTools(registry, McpServerKind.GITHUB, properties.getGithub().isEnabled(), "mcp_github__");
        registerServerTools(registry, McpServerKind.FILESYSTEM, properties.getFilesystem().isEnabled(), "mcp_fs__");
    }

    private void registerServerTools(ToolRegistry registry, McpServerKind kind, boolean enabled, String prefix) {
        if (!enabled) {
            return;
        }
        try (McpStdioClient client = clientFactory.createForDiscovery(kind)) {
            List<McpToolDescriptor> tools = client.listTools();
            for (McpToolDescriptor tool : tools) {
                registry.register(new McpProxyToolExecutor(
                        kind,
                        prefix + tool.name(),
                        tool.name(),
                        tool.description() == null || tool.description().isBlank() ? prefix + tool.name() : tool.description(),
                        tool.inputSchema(),
                        clientFactory,
                        userSettingsService,
                        pathGuard,
                        objectMapper
                ));
            }
            log.info("Registered {} MCP tools for {}", tools.size(), kind);
        } catch (Exception ex) {
            log.warn("Skip MCP tool registration for {}: {}", kind, ex.getMessage());
        }
    }
}
