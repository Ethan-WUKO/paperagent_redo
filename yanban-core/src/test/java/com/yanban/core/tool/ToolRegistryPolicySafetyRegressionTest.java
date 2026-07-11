package com.yanban.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Deterministic MVP safety checks for the registry/model-tool boundary. */
class ToolRegistryPolicySafetyRegressionTest {

    @Test
    void explicitDenyAllHidesRegisteredToolsAndBlocksExecution() {
        ToolRegistry registry = new ToolRegistry().register(new EchoToolExecutor(new ObjectMapper()));

        assertThat(registry.listToolsForModel(Set.of())).isEmpty();
        assertThatThrownBy(() -> registry.execute(new ToolCall("blocked", "echo",
                new ObjectMapper().createObjectNode().put("message", "x")), Set.of()))
                .isInstanceOf(ToolNotFoundException.class);
    }
}
