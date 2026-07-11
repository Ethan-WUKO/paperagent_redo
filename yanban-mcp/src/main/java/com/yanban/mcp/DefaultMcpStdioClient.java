package com.yanban.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.mcp.transport.McpTransport;
import com.yanban.mcp.transport.ProcessMcpTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultMcpStdioClient implements McpStdioClient {

    private final ObjectMapper objectMapper;
    private final McpTransport transport;
    private final McpServerProcessConfig config;
    private final AtomicLong requestIds = new AtomicLong(1);
    private boolean opened;
    private boolean initialized;

    public DefaultMcpStdioClient(McpServerProcessConfig config) {
        this(config, new ProcessMcpTransport(config), new ObjectMapper());
    }

    public DefaultMcpStdioClient(McpServerProcessConfig config, McpTransport transport, ObjectMapper objectMapper) {
        this.config = config;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    @Override
    public void initialize() {
        ensureOpened();
        JsonNode response = request("initialize", initializeParams());
        if (response == null || response.path("result").isMissingNode()) {
            throw new McpClientException("MCP initialize 响应为空");
        }
        notification("notifications/initialized", objectMapper.createObjectNode());
        initialized = true;
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        ensureInitialized();
        JsonNode response = request("tools/list", objectMapper.createObjectNode());
        ArrayNode tools = (ArrayNode) response.path("result").path("tools");
        List<McpToolDescriptor> results = new ArrayList<>();
        if (tools == null) {
            return results;
        }
        for (JsonNode tool : tools) {
            results.add(new McpToolDescriptor(
                    tool.path("name").asText(),
                    tool.path("description").asText(""),
                    tool.path("inputSchema")
            ));
        }
        return results;
    }

    @Override
    public JsonNode callTool(String toolName, JsonNode arguments) {
        ensureInitialized();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null ? objectMapper.createObjectNode() : arguments);
        JsonNode response = request("tools/call", params);
        return response.path("result");
    }

    @Override
    public void close() {
        transport.close();
    }

    private void ensureOpened() {
        if (!opened) {
            transport.open();
            opened = true;
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private JsonNode request(String method, JsonNode params) {
        long id = requestIds.getAndIncrement();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params == null ? objectMapper.createObjectNode() : params);
        try {
            transport.send(objectMapper.writeValueAsString(request));
            while (true) {
                JsonNode response = objectMapper.readTree(transport.read(config.requestTimeout()));
                if (response.hasNonNull("id") && response.path("id").asLong() == id) {
                    if (response.has("error") && !response.path("error").isNull()) {
                        throw new McpClientException("MCP 调用失败: " + response.path("error").toString());
                    }
                    return response;
                }
            }
        } catch (McpClientException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new McpClientException("执行 MCP 请求失败", ex);
        }
    }

    private void notification(String method, JsonNode params) {
        try {
            ObjectNode notification = objectMapper.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            notification.set("params", params == null ? objectMapper.createObjectNode() : params);
            transport.send(objectMapper.writeValueAsString(notification));
        } catch (Exception ex) {
            throw new McpClientException("发送 MCP 通知失败", ex);
        }
    }

    private JsonNode initializeParams() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "yanban-agent");
        clientInfo.put("version", "0.1.0");
        return params;
    }
}
