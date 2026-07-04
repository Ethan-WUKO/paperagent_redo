package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.model.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeServiceTest {

    @Test
    void runSelectsAdapterByStrategy() {
        RuntimeAdapter directAdapter = mock(RuntimeAdapter.class);
        RuntimeAdapter planAdapter = mock(RuntimeAdapter.class);
        AgentRuntimeRequest request = request(AgentStrategy.DIRECT);
        AgentRuntimeResult expected = new AgentRuntimeResult(
                true,
                "ok",
                List.of(ChatMessage.assistant("ok")),
                1,
                null
        );
        when(directAdapter.supports(AgentStrategy.DIRECT)).thenReturn(true);
        when(planAdapter.supports(AgentStrategy.DIRECT)).thenReturn(false);
        when(directAdapter.run(request)).thenReturn(expected);

        AgentRuntimeService service = new AgentRuntimeService(List.of(planAdapter, directAdapter));
        AgentRuntimeResult actual = service.run(request);

        assertThat(actual).isSameAs(expected);
        verify(directAdapter).run(request);
        verify(planAdapter, never()).run(request);
    }

    @Test
    void runFailsWhenNoAdapterSupportsStrategy() {
        RuntimeAdapter adapter = mock(RuntimeAdapter.class);
        when(adapter.supports(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION)).thenReturn(false);
        AgentRuntimeService service = new AgentRuntimeService(List.of(adapter));

        assertThatThrownBy(() -> service.run(request(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLAN_EXECUTE_WITH_REFLECTION");
    }

    private AgentRuntimeRequest request(AgentStrategy strategy) {
        return new AgentRuntimeRequest(
                strategy,
                21L,
                List.of(ChatMessage.user("history")),
                11L,
                "hello",
                "deepseek",
                "deepseek-chat",
                null,
                null,
                8,
                false,
                null,
                null,
                null,
                null,
                List.of(),
                0,
                1,
                "trace-test",
                null
        );
    }
}
