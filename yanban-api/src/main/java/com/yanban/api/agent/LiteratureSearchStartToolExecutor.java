package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import com.yanban.paper.literature.LiteratureSearchTaskRequest;
import org.springframework.stereotype.Component;

@Component
public class LiteratureSearchStartToolExecutor implements ToolExecutor {

    private final LiteratureSearchTaskToolSupport support;
    private final ToolDefinition definition;

    public LiteratureSearchStartToolExecutor(LiteratureSearchTaskToolSupport support, ObjectMapper objectMapper) {
        this.support = support;
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("query").put("type", "string").put("description", "文献检索主题、关键词或自然语言问题");
        properties.putObject("topK").put("type", "integer").put("description", "目标文献数量，默认 8，最大 20");
        properties.putObject("yearFrom").put("type", "integer").put("description", "可选起始年份，例如 2020");
        properties.putObject("includeBibtex").put("type", "boolean").put("description", "是否在结果中保留 BibTeX，默认 true");
        properties.putObject("clientRequestId").put("type", "string").put("description", "前端生成的幂等请求 ID；Agent 内部调用可省略");
        properties.putObject("projectId").put("type", "integer").put("description", "预留项目 ID，可为空");
        parameters.putArray("required").add("query");
        this.definition = new ToolDefinition("literature_search_start", "创建文献检索后台任务，立即返回 taskId，不同步等待检索完成", parameters);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        JsonNode args = call.arguments();
        String query = args == null ? null : args.path("query").asText(null);
        Integer topK = args != null && args.has("topK") ? args.path("topK").asInt() : null;
        Integer yearFrom = args != null && args.has("yearFrom") ? args.path("yearFrom").asInt() : null;
        Boolean includeBibtex = args != null && args.has("includeBibtex") ? args.path("includeBibtex").asBoolean() : null;
        String clientRequestId = args == null ? null : args.path("clientRequestId").asText(null);
        Long projectId = args != null && args.has("projectId") ? args.path("projectId").asLong() : null;
        return support.start(call.id(), definition.name(), new LiteratureSearchTaskRequest(query, topK, yearFrom, includeBibtex, clientRequestId, projectId));
    }
}
