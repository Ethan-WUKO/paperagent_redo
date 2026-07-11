package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ModelProviderException;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class LangChain4jChatModelAdapterTest {

    @Test
    void fallsBackToNonStreamingChatWhenStreamFailsBeforeFirstChunk() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.streamChat(any()))
                .thenReturn(Flux.error(new ModelProviderException(
                        "DeepSeek API stream failed",
                        new RuntimeException("Connection prematurely closed DURING response"))));
        when(provider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("fallback answer"), "stop", null));
        LangChain4jChatModelAdapter adapter = new LangChain4jChatModelAdapter(provider, new ObjectMapper());

        List<ChatChunk> chunks = adapter.stream(request(), runtimeRequest())
                .collectList()
                .block();

        assertThat(chunks).extracting(ChatChunk::content)
                .containsExactly("fallback answer", null);
        assertThat(chunks).last().matches(ChatChunk::done);
        verify(provider).chat(any());
    }

    @Test
    void doesNotFallbackAfterPartialStreamWasAlreadyDelivered() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.streamChat(any()))
                .thenReturn(Flux.concat(
                        Flux.just(ChatChunk.token("partial")),
                        Flux.error(new ModelProviderException(
                                "DeepSeek API stream failed",
                                new RuntimeException("Connection prematurely closed DURING response")))));
        LangChain4jChatModelAdapter adapter = new LangChain4jChatModelAdapter(provider, new ObjectMapper());

        assertThatThrownBy(() -> adapter.stream(request(), runtimeRequest()).collectList().block())
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("DeepSeek API stream failed");
        verify(provider, never()).chat(any());
    }

    private ChatRequest request() {
        return ChatRequest.builder()
                .messages(List.of(UserMessage.from("hello")))
                .parameters(ChatRequestParameters.builder()
                        .modelName("deepseek-v4-flash")
                        .build())
                .build();
    }

    private AgentRuntimeRequest runtimeRequest() {
        return new AgentRuntimeRequest(
                AgentStrategy.DIRECT,
                1L,
                List.of(),
                2L,
                "hello",
                "deepseek",
                "deepseek-v4-flash",
                null,
                null,
                3,
                false,
                null,
                null,
                null,
                null,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                List.of(),
                0,
                0,
                "trace-stream-fallback",
                null,
                null
        );
    }
}
