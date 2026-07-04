package com.yanban.knowledge.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import com.yanban.knowledge.service.KnowledgeSearchService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SearchKnowledgeToolExecutor implements ToolExecutor {

    private final KnowledgeSearchService searchService;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public SearchKnowledgeToolExecutor(KnowledgeSearchService searchService, ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.objectMapper = objectMapper;
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("query").put("type", "string").put("description", "知识库检索关键词或问题");
        properties.putObject("topK").put("type", "integer").put("description", "返回条数，默认 5");
        parameters.putArray("required").add("query");
        this.definition = new ToolDefinition("search_knowledge", "搜索知识库文本分块，返回当前用户可见的结果", parameters);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = call.arguments() == null ? null : call.arguments().path("query").asText(null);
        int topK = call.arguments() != null && call.arguments().has("topK") ? call.arguments().path("topK").asInt(5) : 5;
        Long userId = ToolExecutionContext.getCurrentUserId();
        if (userId == null) {
            return ToolResult.failure(call.id(), definition.name(), "缺少当前用户上下文，无法执行知识库检索");
        }
        List<KnowledgeSearchResult> results = searchService.search(query, userId, topK);
        ArrayNode items = objectMapper.createArrayNode();
        for (KnowledgeSearchResult result : results) {
            ObjectNode item = items.addObject();
            item.put("documentId", result.documentId());
            item.put("filename", result.filename());
            item.put("chunkIndex", result.chunkIndex());
            item.put("chunkText", result.chunkText());
            item.put("score", result.score());
            item.put("isPublic", result.isPublic());
            item.put("citationId", result.citationId());
            item.put("scoreBand", result.scoreBand());
            item.put("source", result.source());
            if (result.rerankScore() != null) {
                item.put("rerankScore", result.rerankScore());
            }
            if (result.rerankReason() != null) {
                item.put("rerankReason", result.rerankReason());
            }
        }
        ObjectNode output = objectMapper.createObjectNode();
        output.set("items", items);
        output.put("guidance", "Use citationId/source when citing knowledge base evidence. If scoreBand is low or no items are returned, state that retrieval confidence is limited before falling back.");
        return ToolResult.success(call.id(), definition.name(), output);
    }
}
