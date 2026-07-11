package com.yanban.knowledge.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolResult;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import com.yanban.knowledge.service.KnowledgeSearchService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SearchKnowledgeToolExecutorTest {

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void executeReturnsSearchItemsUsingImplicitUserContext() {
        KnowledgeSearchService service = Mockito.mock(KnowledgeSearchService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        SearchKnowledgeToolExecutor executor = new SearchKnowledgeToolExecutor(service, objectMapper);
        when(service.search("alpha", 1001L, 3)).thenReturn(List.of(
                new KnowledgeSearchResult(1L, "paper.md", 0, "alpha content", 2.0, false)
        ));
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "alpha");
        args.put("topK", 3);
        ToolExecutionContext.setCurrentUserId(1001L);

        ToolResult result = executor.execute(new ToolCall("call-1", "search_knowledge", args));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("items")).hasSize(1);
        assertThat(result.output().path("items").get(0).path("filename").asText()).isEqualTo("paper.md");
    }

    @Test
    void executeFailsWhenUserContextMissing() {
        KnowledgeSearchService service = Mockito.mock(KnowledgeSearchService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        SearchKnowledgeToolExecutor executor = new SearchKnowledgeToolExecutor(service, objectMapper);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "alpha");

        ToolResult result = executor.execute(new ToolCall("call-1", "search_knowledge", args));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("缺少当前用户上下文");
    }
}
