package com.yanban.core.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import com.yanban.core.rag.KnowledgeContextProvider;
import com.yanban.core.rag.KnowledgeSnippet;
import com.yanban.core.tool.EchoToolExecutor;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class HarnessEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runsToolCallThenFinalAssistantMessage() {
        MockProvider provider = new MockProvider(List.of(
                new ChatResponse(
                        new ChatMessage("assistant", null, List.of(new ToolCall(
                                "call-1",
                                "function",
                                new ToolCall.FunctionCall("echo", "{\"message\":\"hello\"}")
                        )), null),
                        "tool_calls",
                        null
                ),
                new ChatResponse(ChatMessage.assistant("工具返回了 hello"), "stop", null)
        ));
        ToolRegistry registry = new ToolRegistry().register(new EchoToolExecutor(objectMapper));
        HarnessEngine engine = new HarnessEngine(provider, registry, objectMapper);

        HarnessResult result = engine.run(new HarnessRequest(
                List.of(ChatMessage.system("你是研伴 Agent。")),
                1001L,
                "请调用 echo",
                "mock",
                "mock-model",
                null,
                null,
                20,
                true,
                null,
                null,
                null
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("工具返回了 hello");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.get(0).tools()).hasSize(1);
        assertThat(result.messages()).extracting(ChatMessage::role)
                .containsExactly("system", "user", "assistant", "tool", "assistant");
        ChatMessage toolMessage = result.messages().get(3);
        assertThat(toolMessage.toolCallId()).isEqualTo("call-1");
        assertThat(toolMessage.content()).contains("hello");
    }

    @Test
    void stopsWhenModelReturnsPlainTextImmediately() {
        MockProvider provider = new MockProvider(List.of(
                new ChatResponse(ChatMessage.assistant("直接回答"), "stop", null)
        ));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper);

        HarnessResult result = engine.run(new HarnessRequest(List.of(), 1001L, "你好", "mock", "mock-model", 0.1, 128, 20, true, null, null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("直接回答");
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.messages()).extracting(ChatMessage::role).containsExactly("user", "assistant");
    }

    @Test
    void streamsPlainTextTokensToConsumer() {
        StreamingMockProvider provider = new StreamingMockProvider(List.of(List.of(
                ChatChunk.token("he"),
                ChatChunk.token("llo"),
                ChatChunk.done("stop")
        )));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper);
        List<String> streamed = new ArrayList<>();

        HarnessResult result = engine.run(
                new HarnessRequest(
                        List.of(),
                        1001L,
                        "hello",
                        "mock",
                        "mock-model",
                        null,
                        null,
                        20,
                        true,
                        null,
                        null,
                        null,
                        List.of(),
                        0,
                        1,
                        null,
                        null
                ),
                streamed::add
        );

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("hello");
        assertThat(streamed).containsExactly("hello");
        assertThat(provider.chatCalls).isZero();
    }

    @Test
    void fallsBackToNonStreamingWhenStreamDecodingFailsBeforeTokens() {
        StreamFailureThenChatProvider provider = new StreamFailureThenChatProvider(
                new RuntimeException("Encoding error [MALFORMED[1]]"),
                new ChatResponse(ChatMessage.assistant("fallback answer"), "stop", null)
        );
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper);
        List<String> streamed = new ArrayList<>();

        HarnessResult result = engine.run(
                new HarnessRequest(List.of(), 1001L, "hello", "mock", "mock-model",
                        null, null, 20, true, null, null, List.of()),
                streamed::add
        );

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("fallback answer");
        assertThat(streamed).containsExactly("fallback answer");
        assertThat(provider.streamCalls).isEqualTo(1);
        assertThat(provider.chatCalls).isEqualTo(1);
    }

    @Test
    void reconstructsStreamingToolCallDeltasBeforeFinalAnswer() {
        StreamingMockProvider provider = new StreamingMockProvider(List.of(
                List.of(
                        ChatChunk.toolCallDelta(new ChatChunk.ToolCallDelta(0, "call-1", "function", "echo", null)),
                        ChatChunk.toolCallDelta(new ChatChunk.ToolCallDelta(0, null, null, null, "{\"message\":\"")),
                        ChatChunk.toolCallDelta(new ChatChunk.ToolCallDelta(0, null, null, null, "hello\"}")),
                        ChatChunk.done("tool_calls")
                ),
                List.of(
                        ChatChunk.token("done"),
                        ChatChunk.done("stop")
                )
        ));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper);
        List<String> streamed = new ArrayList<>();

        HarnessResult result = engine.run(
                new HarnessRequest(List.of(), 1001L, "call echo", "mock", "mock-model", null, null, 20, true, null, null, null),
                streamed::add
        );

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("done");
        assertThat(streamed).containsExactly("done");
        assertThat(provider.requests).hasSize(2);
        assertThat(result.messages()).extracting(ChatMessage::role)
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(result.messages().get(2).content()).contains("hello");
    }

    @Test
    void suppressesStreamingToolCallPreamble() {
        StreamingMockProvider provider = new StreamingMockProvider(List.of(
                List.of(
                        ChatChunk.token("我来搜索一下。"),
                        ChatChunk.toolCallDelta(new ChatChunk.ToolCallDelta(0, "call-1", "function", "echo", null)),
                        ChatChunk.toolCallDelta(new ChatChunk.ToolCallDelta(0, null, null, null, "{\"message\":\"hello\"}")),
                        ChatChunk.done("tool_calls")
                ),
                List.of(
                        ChatChunk.token("final"),
                        ChatChunk.done("stop")
                )
        ));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper);
        List<String> streamed = new ArrayList<>();

        HarnessResult result = engine.run(
                new HarnessRequest(List.of(), 1001L, "search", "mock", "mock-model", null, null, 20, true, null, null, null),
                streamed::add
        );

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("final");
        assertThat(streamed).containsExactly("final");
        assertThat(result.messages().get(1).role()).isEqualTo("assistant");
        assertThat(result.messages().get(1).content()).isNull();
        assertThat(result.messages().get(1).toolCalls()).hasSize(1);
    }

    @Test
    void retriesAndSuppressesPseudoToolProtocolTextInStreamingDirectAnswer() {
        StreamingMockProvider provider = new StreamingMockProvider(List.of(
                List.of(
                        ChatChunk.token("This is a health question, I will search authoritative information.\n\n"),
                        ChatChunk.token("<||DSML||tool_calls>\n<||DSML||invoke name=\"search_web\">\n"),
                        ChatChunk.token("<||DSML||parameter name=\"query\">diabetes cause</||DSML||parameter>\n"),
                        ChatChunk.token("</||DSML||invoke>\n</||DSML||tool_calls>"),
                        ChatChunk.done("stop")
                ),
                List.of(
                        ChatChunk.token("Diabetes forms when insulin production is insufficient or insulin action is impaired."),
                        ChatChunk.done("stop")
                )
        ));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper);
        List<String> streamed = new ArrayList<>();

        HarnessResult result = engine.run(
                new HarnessRequest(List.of(), 1001L, "How does diabetes form?", "mock", "mock-model",
                        null, null, 20, true, null, null, List.of()),
                streamed::add
        );

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).contains("insulin");
        assertThat(streamed).containsExactly("Diabetes forms when insulin production is insufficient or insulin action is impaired.");
        assertThat(String.join("", streamed)).doesNotContain("DSML", "tool_calls", "search_web");
        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.get(0).tools()).isNull();
        assertThat(provider.requests.get(1).tools()).isNull();
    }

    @Test
    void retriesAndSuppressesChinesePseudoToolProtocolTextInStreamingDirectAnswer() {
        StreamingMockProvider provider = new StreamingMockProvider(List.of(
                List.of(
                        ChatChunk.token("这是一个医学健康问题，我来为您搜索权威的科普信息。\n\n"),
                        ChatChunk.token("<\uff5c\uff5cDSML\uff5c\uff5ctool_calls>\n"),
                        ChatChunk.token("<\uff5c\uff5cDSML\uff5c\uff5cinvoke name=\"search_web\">\n"),
                        ChatChunk.token("<\uff5c\uff5cDSML\uff5c\uff5cparameter name=\"query\" string=\"true\">白血病形成原因 发病机制 医学解释</\uff5c\uff5cDSML\uff5c\uff5cparameter>\n"),
                        ChatChunk.token("</\uff5c\uff5cDSML\uff5c\uff5cinvoke>\n</\uff5c\uff5cDSML\uff5c\uff5ctool_calls>"),
                        ChatChunk.done("stop")
                ),
                List.of(
                        ChatChunk.token("白血病通常源于造血细胞发生遗传改变，导致异常细胞失控增殖。"),
                        ChatChunk.done("stop")
                )
        ));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper);
        List<String> streamed = new ArrayList<>();

        HarnessResult result = engine.run(
                new HarnessRequest(List.of(), 1001L, "白血病是如何形成的？", "mock", "mock-model",
                        null, null, 20, true, null, null, List.of()),
                streamed::add
        );

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).contains("造血细胞");
        assertThat(String.join("", streamed)).doesNotContain("DSML", "tool_calls", "search_web", "我来为您搜索");
        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.get(0).messages().get(0).content()).contains("No tools are available");
    }

    @Test
    void convertsPseudoDsmlToolProtocolToStructuredToolCallWhenToolIsVisible() {
        StreamingMockProvider provider = new StreamingMockProvider(List.of(
                List.of(
                        ChatChunk.token("<\uff5c\uff5cDSML\uff5c\uff5ctool_calls>\n"),
                        ChatChunk.token("<\uff5c\uff5cDSML\uff5c\uff5cinvoke name=\"search_web\">\n"),
                        ChatChunk.token("<\uff5c\uff5cDSML\uff5c\uff5cparameter name=\"query\" string=\"true\">leukemia pathogenesis</\uff5c\uff5cDSML\uff5c\uff5cparameter>\n"),
                        ChatChunk.token("<\uff5c\uff5cDSML\uff5c\uff5cparameter name=\"topK\" string=\"false\">5</\uff5c\uff5cDSML\uff5c\uff5cparameter>\n"),
                        ChatChunk.token("</\uff5c\uff5cDSML\uff5c\uff5cinvoke>\n</\uff5c\uff5cDSML\uff5c\uff5ctool_calls>"),
                        ChatChunk.done("stop")
                ),
                List.of(
                        ChatChunk.token("Leukemia arises when blood-forming cells acquire mutations that disrupt normal growth control."),
                        ChatChunk.done("stop")
                )
        ));
        FakeSearchWebToolExecutor searchWeb = new FakeSearchWebToolExecutor(objectMapper);
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(searchWeb), objectMapper);
        List<String> streamed = new ArrayList<>();

        HarnessResult result = engine.run(
                new HarnessRequest(List.of(), 1001L, "How does leukemia form?", "mock", "mock-model",
                        null, null, 20, true, null, null, List.of("search_web")),
                streamed::add
        );

        assertThat(result.success()).isTrue();
        assertThat(searchWeb.called).isTrue();
        assertThat(searchWeb.lastQuery).isEqualTo("leukemia pathogenesis");
        assertThat(result.assistantContent()).contains("blood-forming cells");
        assertThat(String.join("", streamed)).doesNotContain("DSML", "tool_calls", "search_web");
        assertThat(result.messages()).extracting(ChatMessage::role)
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(result.messages().get(2).toolCallId()).isEqualTo("pseudo_tool_call_1");
    }

    @Test
    void usesKnowledgeContextWhenRagEnabled() {
        MockProvider provider = new MockProvider(List.of(
                new ChatResponse(ChatMessage.assistant("参考知识库回答"), "stop", null)
        ));
        FakeKnowledgeContextProvider knowledge = new FakeKnowledgeContextProvider(List.of(
                new KnowledgeSnippet(1L, "paper.md", 0, "alpha knowledge", 1.0)
        ));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry()
                .register(new EchoToolExecutor(objectMapper))
                .register(new FakeSearchKnowledgeToolExecutor(objectMapper)), objectMapper, knowledge);

        HarnessResult result = engine.run(new HarnessRequest(List.of(), 1001L, "alpha", "mock", "mock-model", null, null, 20, false, null, null, null));

        assertThat(result.success()).isTrue();
        assertThat(knowledge.called).isTrue();
        assertThat(provider.requests.get(0).messages()).extracting(ChatMessage::role)
                .containsExactly("system", "system", "user");
        assertThat(provider.requests.get(0).messages().get(0).content()).contains("alpha knowledge");
        assertThat(provider.requests.get(0).messages().get(1).content()).contains("Tool output contract");
        assertThat(knowledge.topK).isEqualTo(5);
    }

    @Test
    void skipsKnowledgeContextWhenRagDisabled() {
        MockProvider provider = new MockProvider(List.of(
                new ChatResponse(ChatMessage.assistant("不查知识库"), "stop", null)
        ));
        FakeKnowledgeContextProvider knowledge = new FakeKnowledgeContextProvider(List.of(
                new KnowledgeSnippet(1L, "paper.md", 0, "alpha knowledge", 1.0)
        ));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper, knowledge);

        HarnessResult result = engine.run(new HarnessRequest(List.of(), 1001L, "alpha", "mock", "mock-model", null, null, 20, true, null, null, null));

        assertThat(result.success()).isTrue();
        assertThat(knowledge.called).isFalse();
        assertThat(provider.requests.get(0).messages()).extracting(ChatMessage::role)
                .containsExactly("system", "user");
        assertThat(provider.requests.get(0).messages().get(0).content()).contains("Tool output contract");
    }

    @Test
    void preloadsWebEvidenceForCurrentInformationRequests() {
        MockProvider provider = new MockProvider(List.of(
                new ChatResponse(ChatMessage.assistant("基于外部证据回答"), "stop", null)
        ));
        FakeSearchWebToolExecutor searchWeb = new FakeSearchWebToolExecutor(objectMapper);
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(searchWeb), objectMapper);

        HarnessResult result = engine.run(new HarnessRequest(
                List.of(),
                1001L,
                "请搜索最新模型厂商发布",
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
        assertThat(searchWeb.called).isTrue();
        assertThat(searchWeb.lastQuery).contains("latest official model release 2026");
        assertThat(provider.requests).hasSize(1);
        assertThat(provider.requests.get(0).messages()).extracting(ChatMessage::role)
                .containsExactly("system", "system", "system", "user");
        assertThat(provider.requests.get(0).messages().get(0).content()).contains("External web evidence is required");
        assertThat(provider.requests.get(0).messages().get(1).content())
                .contains("Preloaded current web evidence")
                .contains("OpenAI release notes")
                .contains("https://example.com/openai");
        assertThat(provider.requests.get(0).messages().get(2).content()).contains("No tools are available");
    }

    @Test
    void maxStepsTriggersFinalNoToolSynthesisWhenToolResultsExist() {
        ChatResponse toolCallResponse = new ChatResponse(
                new ChatMessage("assistant", null, List.of(new ToolCall(
                        "call-loop",
                        "function",
                        new ToolCall.FunctionCall("echo", "{\"message\":\"loop\"}")
                )), null),
                "tool_calls",
                null
        );
        MockProvider provider = new MockProvider(List.of(
                toolCallResponse,
                toolCallResponse,
                new ChatResponse(ChatMessage.assistant("基于已有工具结果生成最终回答"), "stop", null)
        ));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper);

        HarnessResult result = engine.run(new HarnessRequest(List.of(), 1001L, "循环调用", "mock", "mock-model", null, null, 2, true, null, null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("基于已有工具结果生成最终回答");
        assertThat(result.steps()).isEqualTo(3);
        assertThat(provider.requests).hasSize(3);
        assertThat(provider.requests.get(2).tools()).isNull();
        assertThat(provider.requests.get(2).messages().get(provider.requests.get(2).messages().size() - 1).content())
                .contains("Tool-call budget is exhausted")
                .contains("Do not call any more tools");
        assertThat(result.messages()).extracting(ChatMessage::role)
                .containsExactly("user", "assistant", "tool", "assistant", "tool", "system", "assistant");
    }

    @Test
    void explicitDuplicateToolBudgetBlocksRepeatedToolCallAndSynthesizesFinalAnswer() {
        ChatResponse toolCallResponse = new ChatResponse(
                new ChatMessage("assistant", null, List.of(new ToolCall(
                        "call-loop",
                        "function",
                        new ToolCall.FunctionCall("echo", "{\"message\":\"loop\"}")
                )), null),
                "tool_calls",
                null
        );
        MockProvider provider = new MockProvider(List.of(
                toolCallResponse,
                toolCallResponse,
                new ChatResponse(ChatMessage.assistant("final after duplicate budget"), "stop", null)
        ));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper);

        HarnessResult result = engine.run(new HarnessRequest(
                List.of(),
                1001L,
                "repeat tool",
                "mock",
                "mock-model",
                null,
                null,
                5,
                true,
                null,
                null,
                null,
                null,
                5,
                1,
                null,
                "trace-test"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("final after duplicate budget");
        assertThat(provider.requests).hasSize(3);
        assertThat(provider.requests.get(2).tools()).isNull();
        assertThat(result.messages()).extracting(ChatMessage::role)
                .containsExactly("user", "assistant", "tool", "assistant", "tool", "system", "assistant");
        assertThat(result.messages().get(4).content()).contains("Duplicate tool call blocked");
    }

    @Test
    void explicitToolCallBudgetBlocksExcessToolCallsAndSynthesizesFinalAnswer() {
        ChatResponse toolCallResponse = new ChatResponse(
                new ChatMessage("assistant", null, List.of(
                        new ToolCall("call-1", "function", new ToolCall.FunctionCall("echo", "{\"message\":\"one\"}")),
                        new ToolCall("call-2", "function", new ToolCall.FunctionCall("echo", "{\"message\":\"two\"}"))
                ), null),
                "tool_calls",
                null
        );
        MockProvider provider = new MockProvider(List.of(
                toolCallResponse,
                new ChatResponse(ChatMessage.assistant("final after tool budget"), "stop", null)
        ));
        HarnessEngine engine = new HarnessEngine(provider, new ToolRegistry().register(new EchoToolExecutor(objectMapper)), objectMapper);

        HarnessResult result = engine.run(new HarnessRequest(
                List.of(),
                1001L,
                "too many tools",
                "mock",
                "mock-model",
                null,
                null,
                5,
                true,
                null,
                null,
                null,
                null,
                1,
                5,
                null,
                "trace-test"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("final after tool budget");
        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.get(1).tools()).isNull();
        assertThat(result.messages()).extracting(ChatMessage::role)
                .containsExactly("user", "assistant", "tool", "tool", "system", "assistant");
        assertThat(result.messages().get(3).content()).contains("Tool-call budget exceeded");
    }

    private static class FakeKnowledgeContextProvider implements KnowledgeContextProvider {
        private final List<KnowledgeSnippet> snippets;
        private boolean called;
        private int topK;

        private FakeKnowledgeContextProvider(List<KnowledgeSnippet> snippets) {
            this.snippets = snippets;
        }

        @Override
        public List<KnowledgeSnippet> searchContext(String query, Long userId, int topK) {
            called = true;
            this.topK = topK;
            return snippets;
        }
    }

    private static class FakeSearchWebToolExecutor implements ToolExecutor {
        private final ObjectMapper objectMapper;
        private boolean called;
        private String lastQuery;

        private FakeSearchWebToolExecutor(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(
                    "search_web",
                    "Search web",
                    objectMapper.createObjectNode().put("type", "object")
            );
        }

        @Override
        public ToolResult execute(com.yanban.core.tool.ToolCall call) {
            called = true;
            lastQuery = call.arguments().path("query").asText();
            var output = objectMapper.createObjectNode();
            output.put("query", lastQuery);
            output.put("degraded", false);
            var items = output.putArray("items");
            items.addObject()
                    .put("title", "OpenAI release notes")
                    .put("url", "https://example.com/openai")
                    .put("snippet", "Official model release notes.");
            return ToolResult.success(call.id(), call.name(), output);
        }
    }

    private static class FakeSearchKnowledgeToolExecutor implements ToolExecutor {
        private final ObjectMapper objectMapper;

        private FakeSearchKnowledgeToolExecutor(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(
                    "search_knowledge",
                    "Search private knowledge",
                    objectMapper.createObjectNode().put("type", "object")
            );
        }

        @Override
        public ToolResult execute(com.yanban.core.tool.ToolCall call) {
            return ToolResult.success(call.id(), call.name(), objectMapper.createObjectNode());
        }
    }

    private static class MockProvider implements ChatModelProvider {
        private final Queue<ChatResponse> responses;
        private final List<ChatRequest> requests = new ArrayList<>();

        private MockProvider(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            return responses.remove();
        }

        @Override
        public Flux<ChatChunk> streamChat(ChatRequest request) {
            return Flux.empty();
        }
    }

    private static class StreamingMockProvider implements ChatModelProvider {
        private final Queue<List<ChatChunk>> streams;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int chatCalls;

        private StreamingMockProvider(List<List<ChatChunk>> streams) {
            this.streams = new ArrayDeque<>(streams);
        }

        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            chatCalls++;
            throw new AssertionError("streaming path must not call chat()");
        }

        @Override
        public Flux<ChatChunk> streamChat(ChatRequest request) {
            requests.add(request);
            return Flux.fromIterable(streams.remove());
        }
    }

    private static class StreamFailureThenChatProvider implements ChatModelProvider {
        private final RuntimeException streamFailure;
        private final ChatResponse chatResponse;
        private int streamCalls;
        private int chatCalls;

        private StreamFailureThenChatProvider(RuntimeException streamFailure, ChatResponse chatResponse) {
            this.streamFailure = streamFailure;
            this.chatResponse = chatResponse;
        }

        @Override
        public String providerName() {
            return "mock";
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            chatCalls++;
            return chatResponse;
        }

        @Override
        public Flux<ChatChunk> streamChat(ChatRequest request) {
            streamCalls++;
            return Flux.error(streamFailure);
        }
    }
}
