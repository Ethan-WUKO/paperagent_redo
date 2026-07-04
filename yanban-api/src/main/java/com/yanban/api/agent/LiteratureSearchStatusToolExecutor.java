package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class LiteratureSearchStatusToolExecutor implements ToolExecutor {

    private final LiteratureSearchTaskToolSupport support;
    private final ToolDefinition definition;

    public LiteratureSearchStatusToolExecutor(LiteratureSearchTaskToolSupport support, ObjectMapper objectMapper) {
        this.support = support;
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("taskId").put("type", "integer").put("description", "文献检索任务 ID");
        parameters.putArray("required").add("taskId");
        this.definition = new ToolDefinition("literature_search_status", "查询当前用户文献检索任务状态、阶段和统计信息", parameters);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Long taskId = call.arguments() != null && call.arguments().has("taskId") ? call.arguments().path("taskId").asLong() : null;
        return support.status(call.id(), definition.name(), taskId);
    }
}
