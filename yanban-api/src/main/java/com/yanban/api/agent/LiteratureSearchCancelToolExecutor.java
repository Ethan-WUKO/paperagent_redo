package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class LiteratureSearchCancelToolExecutor implements ToolExecutor {

    private final LiteratureSearchTaskToolSupport support;
    private final ToolDefinition definition;

    public LiteratureSearchCancelToolExecutor(LiteratureSearchTaskToolSupport support, ObjectMapper objectMapper) {
        this.support = support;
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("taskId").put("type", "integer").put("description", "文献检索任务 ID");
        properties.putObject("cancelReason").put("type", "string").put("description", "可选取消原因");
        parameters.putArray("required").add("taskId");
        this.definition = new ToolDefinition("literature_search_cancel", "请求取消当前用户的文献检索任务，不直接强杀执行线程", parameters);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Long taskId = call.arguments() != null && call.arguments().has("taskId") ? call.arguments().path("taskId").asLong() : null;
        String cancelReason = call.arguments() == null ? null : call.arguments().path("cancelReason").asText(null);
        return support.cancel(call.id(), definition.name(), taskId, cancelReason);
    }
}
