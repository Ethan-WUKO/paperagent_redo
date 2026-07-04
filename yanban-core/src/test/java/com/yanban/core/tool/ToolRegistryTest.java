package com.yanban.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registerEchoThenExecuteReturnsSameMessage() {
        ToolRegistry registry = new ToolRegistry().register(new EchoToolExecutor(objectMapper));
        ObjectNode arguments = objectMapper.createObjectNode().put("message", "hello tool");

        ToolResult result = registry.execute(new ToolCall("call-1", "echo", arguments));

        assertThat(result.success()).isTrue();
        assertThat(result.toolCallId()).isEqualTo("call-1");
        assertThat(result.toolName()).isEqualTo("echo");
        assertThat(result.output().path("message").asText()).isEqualTo("hello tool");
    }

    @Test
    void listToolsForModelContainsOpenAiFunctionSpec() {
        ToolRegistry registry = new ToolRegistry().register(new EchoToolExecutor(objectMapper));

        var tools = registry.listToolsForModel();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).type()).isEqualTo("function");
        assertThat(tools.get(0).function().name()).isEqualTo("echo");
        assertThat(tools.get(0).function().description()).contains("原样返回");
        assertThat(tools.get(0).function().parameters().path("properties").has("message")).isTrue();
    }

    @Test
    void duplicateToolNameIsRejected() {
        ToolRegistry registry = new ToolRegistry().register(new EchoToolExecutor(objectMapper));

        assertThatThrownBy(() -> registry.register(new EchoToolExecutor(objectMapper)))
                .isInstanceOf(DuplicateToolException.class)
                .hasMessageContaining("echo");
    }

    @Test
    void emptyAllowedToolSetExposesNoTools() {
        ToolRegistry registry = new ToolRegistry().register(new EchoToolExecutor(objectMapper));

        assertThat(registry.listToolsForModel(Set.of())).isEmpty();
        assertThatThrownBy(() -> registry.execute(
                new ToolCall("call-1", "echo", objectMapper.createObjectNode()),
                Set.of()
        )).isInstanceOf(ToolNotFoundException.class);
    }

    @Test
    void unknownToolNameIsRejected() {
        ToolRegistry registry = new ToolRegistry();

        assertThatThrownBy(() -> registry.execute(new ToolCall("call-1", "missing", objectMapper.createObjectNode())))
                .isInstanceOf(ToolNotFoundException.class)
                .hasMessageContaining("missing");
    }
}
