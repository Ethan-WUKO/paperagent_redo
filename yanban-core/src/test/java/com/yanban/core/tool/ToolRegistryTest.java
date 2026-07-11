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

        assertThat(tools).isEmpty();
    }

    @Test
    void explicitModelPolicyExposesOnlyDescriptorApprovedTools() {
        ToolRegistry registry = new ToolRegistry()
                .register(stub("visible", true, ToolDescriptor.SideEffectType.NONE))
                .register(stub("unknown", true, ToolDescriptor.SideEffectType.UNKNOWN))
                .register(stub("internal", false, ToolDescriptor.SideEffectType.NONE));

        var tools = registry.listToolsForModel(Set.of("visible", "unknown", "internal"));

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).type()).isEqualTo("function");
        assertThat(tools.get(0).function().name()).isEqualTo("visible");
    }

    @Test
    void nullModelPolicyIsRejectedRatherThanExposingEverything() {
        ToolRegistry registry = new ToolRegistry().register(stub("visible", true, ToolDescriptor.SideEffectType.NONE));

        assertThatThrownBy(() -> registry.listToolsForModel(null))
                .isInstanceOf(NullPointerException.class);
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
    void nullOrEmptyExecutionPolicyDeniesRatherThanFallingOpen() {
        ToolRegistry registry = new ToolRegistry().register(new EchoToolExecutor(objectMapper));
        ToolCall call = new ToolCall("call-1", "echo", objectMapper.createObjectNode().put("message", "hello"));

        assertThatThrownBy(() -> registry.execute(call, null)).isInstanceOf(ToolNotFoundException.class);
        assertThatThrownBy(() -> registry.execute(call, Set.of())).isInstanceOf(ToolNotFoundException.class);
    }

    @Test
    void registryKeepsDescriptorTogetherWithExecutor() {
        ToolRegistry registry = new ToolRegistry().register(new EchoToolExecutor(objectMapper));

        ToolDescriptor descriptor = registry.findDescriptor("echo").orElseThrow();

        assertThat(descriptor.version()).isEqualTo("v1");
        assertThat(descriptor.capabilityDomain()).isEqualTo("diagnostic");
        assertThat(descriptor.supportedProfiles()).containsExactly(ToolDescriptor.CapabilityProfile.CHAT);
        assertThat(descriptor.sideEffectType()).isEqualTo(ToolDescriptor.SideEffectType.NONE);
        assertThat(descriptor.resourceScopes()).containsExactly(ToolDescriptor.ResourceScope.SESSION);
        assertThat(descriptor.modelVisible()).isFalse();
    }

    @Test
    void catalogKeepsAuthorizedResearchReadsVisibleAndGatesTaskCancellation() {
        ToolRegistry registry = new ToolRegistry()
                .register(catalogBackedStub("search_web"))
                .register(catalogBackedStub("recommend_literature"))
                .register(catalogBackedStub("paper_task_cancel"));

        ToolDescriptor webSearch = registry.findDescriptor("search_web").orElseThrow();
        ToolDescriptor recommendation = registry.findDescriptor("recommend_literature").orElseThrow();
        ToolCall cancel = new ToolCall("cancel", "paper_task_cancel", objectMapper.createObjectNode());

        assertThat(webSearch.requiredPermissions()).containsExactly("research:web");
        assertThat(webSearch.sideEffectType()).isEqualTo(ToolDescriptor.SideEffectType.EXTERNAL_READ);
        assertThat(webSearch.confirmationPolicy()).isEqualTo(ToolDescriptor.ConfirmationPolicy.NEVER);
        assertThat(recommendation.requiredPermissions()).containsExactly("research:literature");
        assertThat(recommendation.sideEffectType()).isEqualTo(ToolDescriptor.SideEffectType.CREATE);
        assertThatThrownBy(() -> registry.execute(cancel, Set.of("paper_task_cancel")))
                .isInstanceOf(ToolNotFoundException.class);
    }

    @Test
    void unknownToolNameIsRejected() {
        ToolRegistry registry = new ToolRegistry();

        assertThatThrownBy(() -> registry.execute(new ToolCall("call-1", "missing", objectMapper.createObjectNode())))
                .isInstanceOf(ToolNotFoundException.class)
                .hasMessageContaining("missing");
    }

    private ToolExecutor stub(String name, boolean modelVisible, ToolDescriptor.SideEffectType sideEffectType) {
        ToolDefinition definition = new ToolDefinition(name, "stub " + name, objectMapper.createObjectNode().put("type", "object"));
        ToolDescriptor descriptor = new ToolDescriptor(name, "v-test", "test",
                java.util.List.of(ToolDescriptor.CapabilityProfile.CHAT), java.util.List.of(), java.util.List.of(),
                sideEffectType, ToolDescriptor.ConfirmationPolicy.NEVER, ToolDescriptor.AsyncMode.SYNC,
                ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, modelVisible);
        return new ToolExecutor() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.success(call.id(), call.name(), objectMapper.createObjectNode());
            }
        };
    }

    private ToolExecutor catalogBackedStub(String name) {
        ToolDefinition definition = new ToolDefinition(name, "stub " + name, objectMapper.createObjectNode().put("type", "object"));
        return new ToolExecutor() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.success(call.id(), call.name(), objectMapper.createObjectNode());
            }
        };
    }
}
