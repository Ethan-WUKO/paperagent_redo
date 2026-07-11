package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

class LangChain4jToolCallingStrategyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesAllowedToolAndReturnsFinalAnswer() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("search_web")
                                .arguments("{\"query\":\"latest radar paper\"}")
                                .build())))
                        .tokenUsage(new TokenUsage(10, 5, 15))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Here is the answer with sources."))
                        .tokenUsage(new TokenUsage(6, 7, 13))
                        .build());
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel,
                toolProvider(registry),
                objectMapper
        );

        AgentRuntimeResult result = strategy.run(request(List.of("search_web"), 2, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Here is the answer with sources.");
        assertThat(result.toolTrace()).hasSize(1);
        assertThat(result.toolTrace().get(0)).contains("tool=search_web");
        assertThat(result.totalTokens()).isEqualTo(28);
    }

    @Test
    void projectRequestReceivesReadSearchEvidenceInstruction() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(answer("bounded answer"));
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel, toolProvider(new ToolRegistry()), objectMapper);
        AgentRuntimeRequest projectRequest = request(List.of(), 0, 0)
                .withProjectContext(new ProjectRuntimeContext(8L, 42L));

        strategy.run(projectRequest);

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(requestCaptor.capture(), any(AgentRuntimeRequest.class));
        List<String> systemPrompts = requestCaptor.getValue().messages().stream()
                .filter(message -> message instanceof dev.langchain4j.data.message.SystemMessage)
                .map(message -> ((dev.langchain4j.data.message.SystemMessage) message).text())
                .toList();
        assertThat(systemPrompts).anySatisfy(prompt ->
                assertThat(prompt).contains("project_manifest", "project_read_file", "project_search"));
    }

    @Test
    void projectRequestReceivesCoherentMarkdownFormattingInstruction() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(answer("bounded answer"));
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel, toolProvider(new ToolRegistry()), objectMapper);
        AgentRuntimeRequest projectRequest = request(List.of(), 0, 0)
                .withProjectContext(new ProjectRuntimeContext(8L, 42L));

        strategy.run(projectRequest);

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(requestCaptor.capture(), any(AgentRuntimeRequest.class));
        String projectPrompt = requestCaptor.getValue().messages().stream()
                .filter(message -> message instanceof dev.langchain4j.data.message.SystemMessage)
                .map(message -> ((dev.langchain4j.data.message.SystemMessage) message).text())
                .filter(prompt -> prompt.contains("authenticated read-only Project"))
                .findFirst()
                .orElseThrow();
        assertThat(projectPrompt.replaceAll("\\s+", " "))
                .contains("one coherent final answer")
                .contains("short standalone phrase")
                .contains("space after the heading marker")
                .contains("Never format a complete sentence or paragraph as a heading")
                .contains("hyphen followed by a space");
    }

    @Test
    void blocksDuplicateToolCallsBeyondBudget() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("search_web")
                                .arguments("{\"query\":\"latest radar paper\"}")
                                .build())))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-2")
                                .name("search_web")
                                .arguments("{\"query\":\"latest radar paper\"}")
                                .build())))
                        .build());
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel,
                toolProvider(registry),
                objectMapper
        );

        AgentRuntimeResult result = strategy.run(request(List.of("search_web"), 3, 1));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Duplicate tool call blocked");
        assertThat(result.fallbacks()).isNotEmpty();
    }

    @Test
    void canonicalArgumentsPreserveWhitespaceInsideJsonStrings() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "search_web", "{\"query\":\"a b\"}"))
                .thenReturn(toolCall("call-2", "search_web", "{\"query\":\"ab\"}"))
                .thenReturn(answer("Both searches completed."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("search_web"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).hasSize(2);
    }

    @Test
    void canonicalArgumentsBlockEquivalentJsonWithDifferentKeyOrder() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "search_web", "{\"query\":\"x\",\"topK\":1}"))
                .thenReturn(toolCall("call-2", "search_web", "{\"topK\":1,\"query\":\"x\"}"));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("search_web"), 3, 1));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Duplicate tool call blocked");
    }

    @Test
    void allowsRepeatedPollingStatusToolCalls() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("literature_search_status", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("literature_search_status")
                                .arguments("{\"taskId\":3}")
                                .build())))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-2")
                                .name("literature_search_status")
                                .arguments("{\"taskId\":3}")
                                .build())))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Task is still running."))
                        .build());
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel,
                toolProvider(registry),
                objectMapper
        );

        AgentRuntimeResult result = strategy.run(request(List.of("literature_search_status"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Task is still running.");
        assertThat(result.toolTrace()).hasSize(2);
    }

    @Test
    void terminalAsyncStateCannotBePolledAgain() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("terminal_status", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "terminal_status", "{\"taskId\":3}"))
                .thenReturn(toolCall("call-2", "terminal_status", "{\"taskId\":3}"));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("terminal_status"), 3, 1));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("after terminal state");
    }

    @Test
    void unknownAsyncStateCannotAuthorizeAnotherPoll() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("unknown_status", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "unknown_status", "{\"taskId\":3}"))
                .thenReturn(toolCall("call-2", "unknown_status", "{\"taskId\":3}"));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("unknown_status"), 3, 1));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("no observable non-terminal state");
    }

    @Test
    void permitsIdempotentRetryOnlyAfterFailureWithClientRequestId() {
        ToolRegistry registry = new ToolRegistry().register(new RetriableStubToolExecutor("start_export", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                        .id("call-1").name("start_export").arguments("{\"clientRequestId\":\"retry-1\"}").build()))).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                        .id("call-2").name("start_export").arguments("{\"clientRequestId\":\"retry-1\"}").build()))).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("Export started.")).build());

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("start_export"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.toolTrace().get(0)).contains("success=false");
        assertThat(result.toolTrace().get(1)).contains("success=true");
    }

    @Test
    void idempotentRetryBudgetOfOneAllowsExactlyOneRepeat() {
        ToolRegistry registry = new ToolRegistry().register(new RetriableStubToolExecutor("start_export", objectMapper, false));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "start_export", "{\"clientRequestId\":\"retry-1\"}"))
                .thenReturn(toolCall("call-2", "start_export", "{\"clientRequestId\":\"retry-1\"}"))
                .thenReturn(toolCall("call-3", "start_export", "{\"clientRequestId\":\"retry-1\"}"));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("start_export"), 4, 1));

        assertThat(result.success()).isFalse();
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.errorMessage()).contains("Duplicate tool call blocked");
    }

    @Test
    void descriptorVisibilityPreventsPolicyOrOldAliasFromReexposingInternalTool() {
        ToolRegistry registry = new ToolRegistry().register(new InternalStubToolExecutor("literature_search_status", objectMapper));
        LangChain4jToolProvider provider = toolProvider(registry);

        assertThat(provider.provideTools(request(List.of("literature_search_status"), 1, 1)).tools()).isEmpty();
    }

    @Test
    void providerExposesOnlyTheNormalChatResearchPolicy() {
        ToolRegistry registry = new ToolRegistry()
                .register(new InternalStubToolExecutor("search_web", objectMapper))
                .register(new InternalStubToolExecutor("recommend_literature", objectMapper))
                .register(new InternalStubToolExecutor("search_knowledge", objectMapper))
                .register(new InternalStubToolExecutor("paper_task_cancel", objectMapper))
                .register(new InternalStubToolExecutor("literature_search_start", objectMapper));

        assertThat(toolProvider(registry).provideTools(request(
                List.of("search_web", "recommend_literature", "search_knowledge", "paper_task_cancel", "literature_search_start"),
                6, 1)).tools().keySet())
                .extracting(dev.langchain4j.agent.tool.ToolSpecification::name)
                .containsExactlyInAnyOrder("search_web", "recommend_literature", "search_knowledge");
    }

    @Test
    void routingPromptDoesNotRecommendToolsOutsideTheCurrentSpecifications() {
        ToolRegistry registry = new ToolRegistry()
                .register(new InternalStubToolExecutor("search_web", objectMapper))
                .register(new InternalStubToolExecutor("paper_task_cancel", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class))).thenReturn(answer("Direct answer."));

        new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("search_web", "paper_task_cancel"), 6, 1));

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(requestCaptor.capture(), any(AgentRuntimeRequest.class));
        String systemPrompt = requestCaptor.getValue().messages().stream()
                .filter(dev.langchain4j.data.message.SystemMessage.class::isInstance)
                .map(dev.langchain4j.data.message.SystemMessage.class::cast)
                .map(dev.langchain4j.data.message.SystemMessage::text)
                .reduce("", String::concat);
        assertThat(systemPrompt).doesNotContain("paper_task_cancel", "literature_search_start");
        assertThat(requestCaptor.getValue().parameters().toolSpecifications()).extracting(dev.langchain4j.agent.tool.ToolSpecification::name)
                .containsExactly("search_web");
    }

    @Test
    void providerCompatibilityOverloadRequiresResolvedRuntimePolicy() {
        LangChain4jToolProvider provider = toolProvider(new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper)));

        assertThatThrownBy(() -> provider.provideTools(null, java.util.Set.of("search_web")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recordsStructuredToolFailureAsFailureRatherThanSuccessfulExecution() {
        ToolRegistry registry = new ToolRegistry().register(new FailingStubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1").name("search_web").arguments("{}").build())))
                        .build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("Tool failed; answer is limited.")).build());

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("search_web"), 2, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).singleElement().satisfies(trace ->
                assertThat(trace).contains("success=false").contains("upstream timed out"));
    }

    @Test
    void runtimeBudgetComesOnlyFromResolvedPolicy() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("search_web")
                                .arguments("{\"query\":\"polarization FDA-MIMO\"}")
                                .build())))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-2")
                                .name("search_web")
                                .arguments("{\"query\":\"polarization diversity radar\"}")
                                .build())))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Final answer based on the first search result."))
                        .build());
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel,
                toolProvider(registry),
                objectMapper
        );

        AgentRuntimeResult result = strategy.run(requestWithPolicy(
                new ResolvedToolPolicy(List.of("search_web"), 1, 2, "test_policy"), 99, 99));

        assertThat(result.success()).isTrue();
        assertThat(result.runtimeStopSignal()).isEqualTo(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);
        assertThat(result.assistantContent()).isEqualTo("Final answer based on the first search result.");
        assertThat(result.fallbacks()).contains("Tool-call budget exceeded: maxToolCalls=1");
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.toolTrace().get(1)).contains("success=false error=Tool-call budget exceeded");
    }

    @Test
    void annotatedToolReceivesUserIdAsToolMemoryId() {
        ToolRegistry registry = new ToolRegistry().register(new UserAwareStubToolExecutor("search_knowledge", objectMapper));
        LangChain4jToolProvider provider = toolProvider(registry);

        String content = provider
                .provideTools(request(List.of("search_knowledge"), 2, 1), java.util.Set.of("search_knowledge"))
                .toolExecutorByName("search_knowledge")
                .execute(ToolExecutionRequest.builder()
                        .id("call-user")
                        .name("search_knowledge")
                        .arguments("{\"query\":\"project name\"}")
                        .build(), 8L);

        assertThat(content).contains("\"userId\":8");
    }

    @Test
    void streamsFinalAnswerWhenTokenConsumerIsPresent() {
        ToolRegistry registry = new ToolRegistry();
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.stream(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(Flux.just(
                        ChatChunk.token("Hello"),
                        ChatChunk.token(" world"),
                        ChatChunk.done("stop")
                ));
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel,
                toolProvider(registry),
                objectMapper
        );
        List<String> tokens = new ArrayList<>();

        AgentRuntimeResult result = strategy.run(request(List.of(), 0, 1, tokens::add));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Hello world");
        assertThat(tokens).containsExactly("Hello", " world");
    }

    private AgentRuntimeRequest request(List<String> allowedTools, Integer maxToolCalls, Integer maxDuplicateToolCalls) {
        return request(allowedTools, maxToolCalls, maxDuplicateToolCalls, null);
    }

    private AgentRuntimeRequest request(List<String> allowedTools,
                                        Integer maxToolCalls,
                                        Integer maxDuplicateToolCalls,
                                        java.util.function.Consumer<String> tokenConsumer) {
        return new AgentRuntimeRequest(
                AgentStrategy.DIRECT,
                4L,
                List.of(),
                8L,
                "help me search",
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
                allowedTools,
                maxToolCalls,
                maxDuplicateToolCalls,
                "trace-tool",
                tokenConsumer,
                null
        );
    }

    private AgentRuntimeRequest requestWithPolicy(ResolvedToolPolicy toolPolicy,
                                                  Integer legacyMaxToolCalls,
                                                  Integer legacyMaxDuplicateToolCalls) {
        return new AgentRuntimeRequest(
                AgentStrategy.DIRECT,
                4L,
                List.of(),
                8L,
                "help me search",
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
                toolPolicy,
                legacyMaxToolCalls,
                legacyMaxDuplicateToolCalls,
                "trace-tool",
                null,
                null
        );
    }

    private LangChain4jToolProvider toolProvider(ToolRegistry registry) {
        return new LangChain4jToolProvider(
                registry,
                objectMapper,
                new AgentLangChain4jTools(registry, objectMapper)
        );
    }

    private ChatResponse toolCall(String id, String name, String arguments) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                .id(id).name(name).arguments(arguments).build()))).build();
    }

    private ChatResponse answer(String content) {
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).build();
    }

    private static final class StubToolExecutor implements ToolExecutor {

        private final ToolDefinition definition;
        private final ObjectMapper objectMapper;

        private StubToolExecutor(String name, ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "object");
            parameters.putObject("properties")
                    .putObject("query")
                    .put("type", "string");
            parameters.putArray("required").add("query");
            this.definition = new ToolDefinition(name, "stub tool", parameters);
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolDescriptor descriptor() {
            boolean polling = definition.name().endsWith("_status");
            return new ToolDescriptor(definition.name(), "v-test", "test", List.of(ToolDescriptor.CapabilityProfile.CHAT),
                    List.of(), List.of(ToolDescriptor.ResourceScope.EXTERNAL), ToolDescriptor.SideEffectType.NONE,
                    ToolDescriptor.ConfirmationPolicy.NEVER,
                    polling ? ToolDescriptor.AsyncMode.EXTERNAL_TASK : ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE,
                    polling ? ToolDescriptor.RepeatPolicy.POLL_UNTIL_TERMINAL : ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT,
                    true);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            ObjectNode output = objectMapper.createObjectNode();
            output.put("query", call.arguments().path("query").asText());
            output.put("source", "stub");
            if (definition.name().endsWith("_status")) {
                output.put("status", definition.name().startsWith("terminal_") ? "DONE"
                        : definition.name().startsWith("unknown_") ? "MYSTERY" : "RUNNING");
            }
            return ToolResult.success(call.id(), call.name(), output);
        }
    }

    private static final class FailingStubToolExecutor implements ToolExecutor {

        private final ToolDefinition definition;

        private FailingStubToolExecutor(String name, ObjectMapper objectMapper) {
            this.definition = new ToolDefinition(name, "failing " + name,
                    objectMapper.createObjectNode().put("type", "object"));
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolDescriptor descriptor() {
            return visibleSyncDescriptor(definition.name());
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.failure(call.id(), call.name(), com.yanban.core.tool.ToolErrorCode.TIMEOUT,
                    "upstream timed out");
        }
    }

    private static final class UserAwareStubToolExecutor implements ToolExecutor {

        private final ToolDefinition definition;
        private final ObjectMapper objectMapper;

        private UserAwareStubToolExecutor(String name, ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "object");
            parameters.putObject("properties")
                    .putObject("query")
                    .put("type", "string");
            parameters.putArray("required").add("query");
            this.definition = new ToolDefinition(name, "user aware stub tool", parameters);
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolDescriptor descriptor() {
            return visibleSyncDescriptor(definition.name());
        }

        @Override
        public ToolResult execute(ToolCall call) {
            ObjectNode output = objectMapper.createObjectNode();
            output.put("query", call.arguments().path("query").asText());
            output.put("userId", ToolExecutionContext.getCurrentUserId());
            return ToolResult.success(call.id(), call.name(), output);
        }
    }

    private static final class RetriableStubToolExecutor implements ToolExecutor {
        private final ToolDefinition definition;
        private int calls;
        private final boolean succeedOnSecondCall;

        private RetriableStubToolExecutor(String name, ObjectMapper objectMapper) {
            this(name, objectMapper, true);
        }

        private RetriableStubToolExecutor(String name, ObjectMapper objectMapper, boolean succeedOnSecondCall) {
            this.definition = new ToolDefinition(name, "retriable " + name,
                    objectMapper.createObjectNode().put("type", "object"));
            this.succeedOnSecondCall = succeedOnSecondCall;
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(definition.name(), "v-test", "export", List.of(ToolDescriptor.CapabilityProfile.CHAT),
                    List.of(), List.of(ToolDescriptor.ResourceScope.EXTERNAL), ToolDescriptor.SideEffectType.CREATE,
                    ToolDescriptor.ConfirmationPolicy.NEVER, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.REQUIRED_KEY, ToolDescriptor.RepeatPolicy.ALLOW_LIMITED, true);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            calls++;
            return calls == 1 || !succeedOnSecondCall
                    ? ToolResult.failure(call.id(), call.name(), com.yanban.core.tool.ToolErrorCode.TIMEOUT, "temporary timeout")
                    : ToolResult.success(call.id(), call.name(), new ObjectMapper().createObjectNode().put("started", true));
        }
    }

    private static final class InternalStubToolExecutor implements ToolExecutor {
        private final ToolDefinition definition;

        private InternalStubToolExecutor(String name, ObjectMapper objectMapper) {
            this.definition = new ToolDefinition(name, "internal " + name,
                    objectMapper.createObjectNode().put("type", "object"));
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.success(call.id(), call.name(), new ObjectMapper().createObjectNode());
        }
    }

    private static ToolDescriptor visibleSyncDescriptor(String name) {
        return new ToolDescriptor(name, "v-test", "test", List.of(ToolDescriptor.CapabilityProfile.CHAT),
                List.of(), List.of(ToolDescriptor.ResourceScope.EXTERNAL), ToolDescriptor.SideEffectType.NONE,
                ToolDescriptor.ConfirmationPolicy.NEVER, ToolDescriptor.AsyncMode.SYNC,
                ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, true);
    }
}
