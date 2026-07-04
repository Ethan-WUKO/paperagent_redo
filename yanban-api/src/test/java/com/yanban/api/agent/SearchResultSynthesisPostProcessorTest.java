package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.harness.HarnessRequest;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SearchResultSynthesisPostProcessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void processRanksAndSummarizesSearchResultsWithEphemeralSubAgent() {
        FakeModelProvider provider = new FakeModelProvider("""
                {
                  "query": "multi agent architecture",
                  "degraded": false,
                  "conclusion": "Hierarchical and distributed patterns are the most relevant starting points.",
                  "selectedTopK": [
                    {
                      "rank": 1,
                      "title": "Multi-agent systems overview",
                      "source": "https://example.com/mas",
                      "evidence": "Discusses hierarchical and distributed control.",
                      "reason": "Best coverage of architecture taxonomy."
                    }
                  ],
                  "limitations": []
                }
                """);
        SearchResultSynthesisPostProcessor processor = new SearchResultSynthesisPostProcessor(provider, objectMapper);

        ToolResult processed = processor.process(
                ToolResult.success("call_1", "search_web", searchOutput()),
                request()
        );

        assertThat(processed.success()).isTrue();
        assertThat(processed.output().path("subAgent").asText()).isEqualTo("search_result_synthesizer");
        assertThat(processed.output().path("sourceTool").asText()).isEqualTo("search_web");
        assertThat(processed.output().path("conclusion").asText()).contains("Hierarchical");
        assertThat(processed.output().path("selectedTopK")).hasSize(1);
        assertThat(processed.output().path("selectedTopK").get(0).path("rank").asInt()).isEqualTo(1);
        assertThat(provider.requests).hasSize(1);
        assertThat(provider.requests.get(0).tools()).isNull();
        assertThat(provider.requests.get(0).messages().get(1).content())
                .contains("Parent task")
                .contains("Multi-agent systems overview");
    }

    @Test
    void processFallsBackToRankedCandidatesWhenSubAgentFails() {
        FakeModelProvider provider = new FakeModelProvider(null);
        provider.fail = true;
        SearchResultSynthesisPostProcessor processor = new SearchResultSynthesisPostProcessor(provider, objectMapper);

        ToolResult processed = processor.process(
                ToolResult.success("call_1", "search_web", searchOutput()),
                request()
        );

        assertThat(processed.success()).isTrue();
        assertThat(processed.output().path("degraded").asBoolean()).isTrue();
        assertThat(processed.output().path("selectedTopK")).hasSize(2);
        assertThat(processed.output().path("selectedTopK").get(0).path("title").asText())
                .isEqualTo("Multi-agent systems overview");
        assertThat(processed.output().path("limitations").get(0).asText()).contains("synthesis failed");
    }

    private ObjectNode searchOutput() {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("query", "multi agent architecture");
        output.put("degraded", false);
        var items = output.putArray("items");
        items.addObject()
                .put("title", "Multi-agent systems overview")
                .put("url", "https://example.com/mas")
                .put("snippet", "Discusses hierarchical and distributed control.");
        items.addObject()
                .put("title", "Agent coordination notes")
                .put("url", "https://example.com/coordination")
                .put("snippet", "Covers coordination protocols.");
        return output;
    }

    private HarnessRequest request() {
        return new HarnessRequest(
                List.of(),
                7L,
                "Research multi-agent collaboration architecture patterns.",
                "mock",
                "mock-model",
                null,
                null,
                8,
                false,
                "test-api-key",
                null,
                null
        );
    }

    private static class FakeModelProvider implements ChatModelProvider {
        private final String content;
        private final List<ChatRequest> requests = new ArrayList<>();
        private boolean fail;

        private FakeModelProvider(String content) {
            this.content = content;
        }

        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            if (fail) {
                throw new RuntimeException("sub-agent unavailable");
            }
            return new ChatResponse(ChatMessage.assistant(content), "stop", null);
        }

        @Override
        public Flux<ChatChunk> streamChat(ChatRequest request) {
            return Flux.empty();
        }
    }
}
