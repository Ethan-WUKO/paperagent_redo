package com.yanban.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class EchoToolExecutor implements ToolExecutor {

    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public EchoToolExecutor() {
        this(new ObjectMapper());
    }

    public EchoToolExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("message")
                .put("type", "string")
                .put("description", "需要原样返回的消息");
        parameters.putArray("required").add("message");
        this.definition = new ToolDefinition("echo", "原样返回输入 message，用于 Harness 与 ToolRegistry 测试", parameters);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        JsonNode arguments = call.arguments();
        String message = arguments == null || arguments.get("message") == null ? "" : arguments.get("message").asText();
        ObjectNode output = objectMapper.createObjectNode();
        output.put("message", message);
        return ToolResult.success(call.id(), definition.name(), output);
    }
}
