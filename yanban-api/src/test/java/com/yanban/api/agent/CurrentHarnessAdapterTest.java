package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.harness.HarnessEngine;
import com.yanban.core.harness.HarnessRequest;
import com.yanban.core.harness.HarnessResult;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CurrentHarnessAdapterTest {

    @Mock
    HarnessEngine harnessEngine;

    @Test
    void supportsDirectAndSingleStepReactOnly() {
        CurrentHarnessAdapter adapter = new CurrentHarnessAdapter(harnessEngine);

        assertThat(adapter.supports(AgentStrategy.DIRECT)).isTrue();
        assertThat(adapter.supports(AgentStrategy.SINGLE_STEP_REACT)).isTrue();
        assertThat(adapter.supports(AgentStrategy.PLAN_EXECUTE)).isFalse();
    }

    @Test
    void runDelegatesToHarnessEngineWithStreamingConsumer() {
        CurrentHarnessAdapter adapter = new CurrentHarnessAdapter(harnessEngine);
        AtomicReference<String> streamed = new AtomicReference<>();
        Consumer<String> tokenConsumer = streamed::set;
        when(harnessEngine.run(any(HarnessRequest.class), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> consumer = invocation.getArgument(1);
                    consumer.accept("partial token");
                    return HarnessResult.success("final answer", List.of(ChatMessage.assistant("final answer")), 1);
                });

        AgentRuntimeResult result = adapter.run(new AgentRuntimeRequest(
                AgentStrategy.SINGLE_STEP_REACT,
                21L,
                List.of(ChatMessage.system("ctx")),
                11L,
                "hello",
                "deepseek",
                "deepseek-chat",
                null,
                null,
                8,
                false,
                null,
                "api-key",
                "https://example.test",
                "skill prompt",
                List.of("search_web"),
                1,
                1,
                "trace-72",
                tokenConsumer
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("final answer");
        assertThat(streamed.get()).isEqualTo("partial token");

        ArgumentCaptor<HarnessRequest> requestCaptor = ArgumentCaptor.forClass(HarnessRequest.class);
        verify(harnessEngine).run(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().history()).containsExactly(ChatMessage.system("ctx"));
        assertThat(requestCaptor.getValue().userMessage()).isEqualTo("hello");
        assertThat(requestCaptor.getValue().traceId()).isEqualTo("trace-72");
        assertThat(requestCaptor.getValue().allowedToolNames()).containsExactly("search_web");
    }
}
