package com.yanban.core.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.tool.EchoToolExecutor;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class HarnessRagToolDisableTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ragDisabledRemovesSearchKnowledgeToolFromModelVisibleTools() {
        CapturingProvider provider = new CapturingProvider();
        ToolRegistry registry = new ToolRegistry()
                .register(new EchoToolExecutor(objectMapper))
                .register(new SearchKnowledgeStubTool(objectMapper));
        HarnessEngine engine = new HarnessEngine(provider, registry, objectMapper);

        HarnessResult result = engine.run(new HarnessRequest(
                List.of(),
                1L,
                "测试",
                "mock",
                "mock-model",
                null,
                null,
                5,
                true,
                null,
                null,
                null
        ));

        assertThat(result.success()).isTrue();
        assertThat(provider.lastRequest.tools()).extracting(tool -> tool.function().name())
                .contains("echo")
                .doesNotContain("search_knowledge");
    }

    private static class CapturingProvider implements ChatModelProvider {
        private ChatRequest lastRequest;

        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            this.lastRequest = request;
            return new ChatResponse(ChatMessage.assistant("ok"), "stop", null);
        }

        @Override
        public Flux<ChatChunk> streamChat(ChatRequest request) {
            return Flux.empty();
        }
    }

    private static class SearchKnowledgeStubTool implements ToolExecutor {
        private final ToolDefinition definition;

        private SearchKnowledgeStubTool(ObjectMapper objectMapper) {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            this.definition = new ToolDefinition("search_knowledge", "stub", schema);
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.success(call.id(), definition.name(), new ObjectMapper().createObjectNode());
        }
    }
}
