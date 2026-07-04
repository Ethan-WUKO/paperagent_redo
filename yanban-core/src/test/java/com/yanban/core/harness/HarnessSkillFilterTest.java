package com.yanban.core.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import com.yanban.core.tool.EchoToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class HarnessSkillFilterTest {

    @Test
    void disallowedToolFailsExecution() {
        ChatModelProvider provider = new ChatModelProvider() {
            @Override public String providerName() { return "mock"; }
            @Override public ChatResponse chat(ChatRequest request) {
                return new ChatResponse(new ChatMessage("assistant", null, List.of(new ToolCall(
                        "call-1", "function", new ToolCall.FunctionCall("echo", "{\"message\":\"hello\"}")
                )), null), "tool_calls", null);
            }
            @Override public Flux<ChatChunk> streamChat(ChatRequest request) { return Flux.empty(); }
        };
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(new ObjectMapper())), new ObjectMapper());

        HarnessResult result = engine.run(new HarnessRequest(
                List.of(), 1L, "test", "mock", "mock-model", null, null, 1, true, null, "skill prompt", List.of("mcp_fs__read_file")
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.messages().get(0).content()).contains("skill prompt");
    }
}
