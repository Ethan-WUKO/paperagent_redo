package com.yanban.core.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import com.yanban.core.model.ToolSpec;
import com.yanban.core.rag.KnowledgeContextProvider;
import com.yanban.core.rag.KnowledgeSnippet;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

public class HarnessEngine {

    private static final Logger log = LoggerFactory.getLogger(HarnessEngine.class);
    private static final int TOOL_PROTOCOL_PREVIEW_LIMIT = 240;
    private static final int STREAM_GUARD_RELEASE_CHARS = 160;
    private static final int STREAM_GUARD_TRAILING_CHARS = 48;
    private static final int DEFAULT_MAX_TOOL_CALLS = 6;
    private static final int DEFAULT_MAX_DUPLICATE_TOOL_CALLS = 3;
    private static final int DEFAULT_RAG_TOP_K = 5;
    private static final Pattern DSML_INVOKE_PATTERN = Pattern.compile(
            "(?is)<\\s*\\|\\|dsml\\|\\|\\s*invoke\\s+name\\s*=\\s*[\"']([^\"']+)[\"']\\s*>(.*?)</\\s*\\|\\|dsml\\|\\|\\s*invoke\\s*>"
    );
    private static final Pattern DSML_PARAMETER_PATTERN = Pattern.compile(
            "(?is)<\\s*\\|\\|dsml\\|\\|\\s*parameter\\s+name\\s*=\\s*[\"']([^\"']+)[\"'](?:\\s+string\\s*=\\s*[\"']([^\"']+)[\"'])?\\s*>(.*?)</\\s*\\|\\|dsml\\|\\|\\s*parameter\\s*>"
    );
    private static final List<String> CURRENT_INFO_KEYWORDS = List.of(
            "最新", "当前", "现在", "最近", "今日", "今天", "实时", "新闻", "发布",
            "latest", "current", "recent", "today", "now", "news", "released", "release"
    );

    private final ChatModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final KnowledgeContextProvider knowledgeContextProvider;
    private final List<ToolResultPostProcessor> toolResultPostProcessors;
    private final int ragTopK;

    public HarnessEngine(ChatModelProvider modelProvider, ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this(modelProvider, toolRegistry, objectMapper, null);
    }

    public HarnessEngine(ChatModelProvider modelProvider,
                         ToolRegistry toolRegistry,
                         ObjectMapper objectMapper,
                         KnowledgeContextProvider knowledgeContextProvider) {
        this(modelProvider, toolRegistry, objectMapper, knowledgeContextProvider, List.of());
    }

    public HarnessEngine(ChatModelProvider modelProvider,
                         ToolRegistry toolRegistry,
                         ObjectMapper objectMapper,
                         KnowledgeContextProvider knowledgeContextProvider,
                         Collection<ToolResultPostProcessor> toolResultPostProcessors) {
        this(modelProvider, toolRegistry, objectMapper, knowledgeContextProvider, toolResultPostProcessors, DEFAULT_RAG_TOP_K);
    }

    public HarnessEngine(ChatModelProvider modelProvider,
                         ToolRegistry toolRegistry,
                         ObjectMapper objectMapper,
                         KnowledgeContextProvider knowledgeContextProvider,
                         Collection<ToolResultPostProcessor> toolResultPostProcessors,
                         int ragTopK) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.knowledgeContextProvider = knowledgeContextProvider;
        this.toolResultPostProcessors = toolResultPostProcessors == null
                ? List.of()
                : List.copyOf(toolResultPostProcessors);
        this.ragTopK = Math.max(1, ragTopK);
    }

    public HarnessResult run(HarnessRequest request) {
        return run(request, null);
    }

    public HarnessResult run(HarnessRequest request, Consumer<String> tokenConsumer) {
        List<ChatMessage> messages = new ArrayList<>(request.history());
        Set<String> allowedTools = resolveAllowedTools(request);
        if (request.skillPrompt() != null && !request.skillPrompt().isBlank()) {
            messages.add(ChatMessage.system(request.skillPrompt()));
        }
        if (!request.ragDisabled() && knowledgeContextProvider != null && isToolAllowed("search_knowledge", allowedTools)) {
            List<KnowledgeSnippet> snippets = knowledgeContextProvider.searchContext(
                    request.userMessage(),
                    request.userId(),
                    ragTopK
            );
            if (!snippets.isEmpty()) {
                messages.add(ChatMessage.system(buildKnowledgeContext(snippets)));
            } else {
                log.info("Harness RAG returned no snippets provider={} userId={} traceId={}",
                        request.provider(),
                        request.userId(),
                        request.traceId());
            }
        }
        if (requiresCurrentWebEvidence(request.userMessage(), allowedTools)) {
            messages.add(ChatMessage.system(currentWebEvidenceInstruction()));
            messages.add(ChatMessage.system(buildCurrentWebEvidenceContext(preloadCurrentWebEvidence(request, allowedTools))));
            allowedTools = withoutAllowedTool(allowedTools, "search_web");
        }
        messages.add(ChatMessage.user(request.userMessage()));

        int steps = 0;
        int toolCalls = 0;
        boolean sawSuccessfulToolResult = false;
        Map<String, Integer> toolCallCounts = new LinkedHashMap<>();
        for (; steps < request.maxSteps(); steps++) {
            if (deadlineExceeded(request.deadlineAt())) {
                String error = "Harness deadline exceeded before model step " + (steps + 1);
                log.warn("{} traceId={}", error, request.traceId());
                if (sawSuccessfulToolResult) {
                    return synthesizeFinalAnswerAfterToolBudget(request, messages, steps, error, tokenConsumer);
                }
                return HarnessResult.failure(error, messages, steps);
            }

            List<ToolSpec> visibleTools = toolRegistry.listToolsForModel(allowedTools);
            List<ChatMessage> modelMessages = messagesForModel(messages, visibleTools);
            ChatRequest chatRequest = new ChatRequest(
                    request.provider(),
                    request.model(),
                    List.copyOf(modelMessages),
                    request.temperature(),
                    request.maxTokens(),
                    visibleTools.isEmpty() ? null : visibleTools,
                    request.apiKey(),
                    request.apiUrl(),
                    null,
                    null,
                    request.traceId()
            );
            ChatResponse response = callModel(request, chatRequest, "agent_step", tokenConsumer);
            ChatMessage assistantMessage = suppressToolCallPreamble(response == null ? null : response.message());
            if (isPseudoToolOutput(assistantMessage)) {
                List<ToolCall> pseudoToolCalls = parsePseudoToolCalls(assistantMessage.content(), visibleTools);
                if (!pseudoToolCalls.isEmpty()) {
                    assistantMessage = new ChatMessage("assistant", null, pseudoToolCalls, null);
                } else {
                    log.warn("Harness blocked pseudo tool-call text provider={} step={} contentPreview={} traceId={}",
                            request.provider(),
                            steps + 1,
                            abbreviate(assistantMessage.content()),
                            request.traceId());
                    response = retryAfterPseudoToolOutput(request, messages, visibleTools, tokenConsumer);
                    assistantMessage = suppressToolCallPreamble(response == null ? null : response.message());
                    pseudoToolCalls = parsePseudoToolCalls(assistantMessage == null ? null : assistantMessage.content(), visibleTools);
                    if (!pseudoToolCalls.isEmpty()) {
                        assistantMessage = new ChatMessage("assistant", null, pseudoToolCalls, null);
                    } else if (isPseudoToolOutput(assistantMessage)) {
                        String error = "Model returned pseudo tool-call text after retry";
                        log.warn("{} provider={} step={} contentPreview={} traceId={}",
                                error,
                                request.provider(),
                                steps + 1,
                                abbreviate(assistantMessage == null ? null : assistantMessage.content()),
                                request.traceId());
                        return HarnessResult.failure(error, messages, steps + 1);
                    }
                }
            }
            messages.add(assistantMessage);

            if (chatRequest.tools() != null && !chatRequest.tools().isEmpty()) {
                log.info("Harness step={} provider={} toolsVisible={} finishReason={} traceId={}",
                        steps + 1,
                        request.provider(),
                        extractToolNames(chatRequest.tools()),
                        response == null ? null : response.finishReason(),
                        request.traceId());
            }

            List<com.yanban.core.model.ToolCall> modelToolCalls = assistantMessage == null
                    ? null
                    : assistantMessage.toolCalls();
            if ((modelToolCalls == null || modelToolCalls.isEmpty())
                    && chatRequest.tools() != null
                    && !chatRequest.tools().isEmpty()
                    && looksLikePseudoToolCall(assistantMessage == null ? null : assistantMessage.content())) {
                log.warn("Harness suspected pseudo tool-call output provider={} step={} contentPreview={} traceId={}",
                        request.provider(),
                        steps + 1,
                        abbreviate(assistantMessage == null ? null : assistantMessage.content()),
                        request.traceId());
            }
            if (modelToolCalls == null || modelToolCalls.isEmpty()) {
                return HarnessResult.success(
                        assistantMessage == null ? null : assistantMessage.content(),
                        messages,
                        steps + 1
                );
            }

            for (int toolCallIndex = 0; toolCallIndex < modelToolCalls.size(); toolCallIndex++) {
                com.yanban.core.model.ToolCall modelToolCall = modelToolCalls.get(toolCallIndex);
                if (deadlineExceeded(request.deadlineAt())) {
                    String error = "Harness deadline exceeded before tool execution";
                    log.warn("{} traceId={} provider={}", error, request.traceId(), request.provider());
                    appendSkippedToolMessages(messages, modelToolCalls, toolCallIndex, error);
                    if (sawSuccessfulToolResult) {
                        return synthesizeFinalAnswerAfterToolBudget(request, messages, steps, error, tokenConsumer);
                    }
                    return HarnessResult.failure(error, messages, steps + 1);
                }
                if (toolCalls >= maxToolCalls(request)) {
                    String error = "Tool-call budget exceeded: maxToolCalls=" + maxToolCalls(request);
                    log.warn("{} traceId={} provider={}", error, request.traceId(), request.provider());
                    appendSkippedToolMessages(messages, modelToolCalls, toolCallIndex, error);
                    if (sawSuccessfulToolResult) {
                        return synthesizeFinalAnswerAfterToolBudget(request, messages, steps + 1, error, tokenConsumer);
                    }
                    return HarnessResult.failure(error, messages, steps + 1);
                }

                String signature = toolCallSignature(modelToolCall);
                int duplicateCount = toolCallCounts.getOrDefault(signature, 0);
                if (duplicateCount >= maxDuplicateToolCalls(request)) {
                    String error = "Duplicate tool call blocked: " + abbreviate(signature);
                    log.warn("{} traceId={} provider={}", error, request.traceId(), request.provider());
                    appendSkippedToolMessages(messages, modelToolCalls, toolCallIndex, error);
                    if (sawSuccessfulToolResult) {
                        return synthesizeFinalAnswerAfterToolBudget(request, messages, steps + 1, error, tokenConsumer);
                    }
                    return HarnessResult.failure(error, messages, steps + 1);
                }
                toolCallCounts.put(signature, duplicateCount + 1);
                toolCalls++;

                long startNanos = System.nanoTime();
                ToolResult result = executeTool(modelToolCall, request.userId(), allowedTools);
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("Harness tool step={} tool={} success={} durationMs={} traceId={}",
                        steps + 1,
                        result.toolName(),
                        result.success(),
                        durationMs,
                        request.traceId());
                ToolResult processedResult = postProcessToolResult(result, request);
                sawSuccessfulToolResult = sawSuccessfulToolResult || processedResult.success();
                messages.add(ChatMessage.tool(modelToolCall.id(), toToolMessageContent(processedResult)));
            }
        }

        String error = "Harness exceeded max_steps=" + request.maxSteps();
        log.warn("{} traceId={}", error, request.traceId());
        if (sawSuccessfulToolResult) {
            return synthesizeFinalAnswerAfterToolBudget(request, messages, steps, error, tokenConsumer);
        }
        return HarnessResult.failure(error, messages, steps);
    }

    private ChatResponse callModel(HarnessRequest request, ChatRequest chatRequest, String phase) {
        return callModel(request, chatRequest, phase, null);
    }

    private ChatResponse callModel(HarnessRequest request,
                                   ChatRequest chatRequest,
                                   String phase,
                                   Consumer<String> tokenConsumer) {
        long startNanos = System.nanoTime();
        try {
            boolean streamTokensImmediately = chatRequest.tools() == null || chatRequest.tools().isEmpty();
            ChatResponse response = tokenConsumer == null
                    ? modelProvider.chat(chatRequest)
                    : streamModel(chatRequest, tokenConsumer, streamTokensImmediately);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            ChatResponse.Usage usage = response == null ? null : response.usage();
            log.info("Harness model phase={} provider={} model={} durationMs={} finishReason={} promptTokens={} completionTokens={} totalTokens={} traceId={}",
                    phase,
                    request.provider(),
                    request.model(),
                    durationMs,
                    response == null ? null : response.finishReason(),
                    usage == null ? null : usage.promptTokens(),
                    usage == null ? null : usage.completionTokens(),
                    usage == null ? null : usage.totalTokens(),
                    request.traceId());
            return response;
        } catch (RuntimeException ex) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("Harness model failed phase={} provider={} model={} durationMs={} traceId={} error={}",
                    phase,
                    request.provider(),
                    request.model(),
                    durationMs,
                    request.traceId(),
                    blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private ChatResponse streamModel(ChatRequest chatRequest,
                                     Consumer<String> tokenConsumer,
                                     boolean streamTokensImmediately) {
        StringBuilder content = new StringBuilder();
        StringBuilder pendingStream = new StringBuilder();
        Map<Integer, StreamingToolCallBuilder> toolCallBuilders = new LinkedHashMap<>();
        String[] finishReason = new String[1];
        boolean[] streamReleased = new boolean[1];
        boolean[] streamSuppressed = new boolean[1];
        boolean[] streamEmitted = new boolean[1];
        try {
            modelProvider.streamChat(chatRequest)
                    .doOnNext(chunk -> {
                        if (chunk == null) {
                            return;
                        }
                        if (chunk.content() != null && !chunk.content().isEmpty()) {
                            content.append(chunk.content());
                            if (streamTokensImmediately) {
                                pendingStream.append(chunk.content());
                                String pending = pendingStream.toString();
                                if (looksLikePseudoToolCall(pending) || looksLikeToolSearchPreamble(pending)) {
                                    streamSuppressed[0] = true;
                                    pendingStream.setLength(0);
                                } else if (!streamSuppressed[0] && !streamReleased[0] && shouldReleaseBufferedStream(pending)) {
                                    emitToken(tokenConsumer, pending, streamEmitted);
                                    pendingStream.setLength(0);
                                    streamReleased[0] = true;
                                } else if (!streamSuppressed[0] && streamReleased[0] && pendingStream.length() > STREAM_GUARD_TRAILING_CHARS) {
                                    int flushLength = pendingStream.length() - STREAM_GUARD_TRAILING_CHARS;
                                    emitToken(tokenConsumer, pendingStream.substring(0, flushLength), streamEmitted);
                                    pendingStream.delete(0, flushLength);
                                }
                            }
                        }
                        for (ChatChunk.ToolCallDelta delta : chunk.toolCallDeltas()) {
                            toolCallBuilders
                                    .computeIfAbsent(delta.index(), StreamingToolCallBuilder::new)
                                    .append(delta);
                        }
                        if (chunk.done()) {
                            finishReason[0] = chunk.finishReason();
                        }
                    })
                    .blockLast();
        } catch (RuntimeException ex) {
            if (!streamEmitted[0] && isStreamDecodingFailure(ex)) {
                log.warn("Harness stream decoding failed before emitting tokens; falling back to non-streaming model call provider={} model={} error={}",
                        chatRequest.provider(),
                        chatRequest.model(),
                        blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()));
                return fallbackToNonStreaming(chatRequest, tokenConsumer);
            }
            throw ex;
        }

        List<ToolCall> toolCalls = toolCallBuilders.values().stream()
                .map(StreamingToolCallBuilder::build)
                .filter(java.util.Objects::nonNull)
                .toList();
        String assistantContent = toolCalls.isEmpty() && content.length() > 0 ? content.toString() : null;
        if (streamTokensImmediately
                && !streamSuppressed[0]
                && pendingStream.length() > 0
                && !looksLikePseudoToolCall(assistantContent)
                && !looksLikeToolSearchPreamble(assistantContent)) {
            emitToken(tokenConsumer, pendingStream.toString(), streamEmitted);
        }
        if (!streamTokensImmediately
                && toolCalls.isEmpty()
                && assistantContent != null
                && !looksLikePseudoToolCall(assistantContent)
                && !looksLikeToolSearchPreamble(assistantContent)) {
            emitToken(tokenConsumer, assistantContent, streamEmitted);
        }
        return new ChatResponse(
                new ChatMessage(
                        "assistant",
                        assistantContent,
                        toolCalls.isEmpty() ? null : toolCalls,
                        null
                ),
                finishReason[0],
                null
        );
    }

    private ChatResponse fallbackToNonStreaming(ChatRequest chatRequest,
                                                Consumer<String> tokenConsumer) {
        ChatResponse response = modelProvider.chat(chatRequest);
        ChatMessage message = response == null ? null : response.message();
        List<ToolCall> toolCalls = message == null ? null : message.toolCalls();
        String content = message == null ? null : message.content();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        if (!hasToolCalls
                && StringUtils.hasText(content)
                && !looksLikePseudoToolCall(content)
                && !looksLikeToolSearchPreamble(content)) {
            boolean[] emitted = new boolean[1];
            emitToken(tokenConsumer, content, emitted);
        }
        return response;
    }

    private void emitToken(Consumer<String> tokenConsumer, String token, boolean[] emitted) {
        if (token == null || token.isEmpty()) {
            return;
        }
        tokenConsumer.accept(token);
        emitted[0] = true;
    }

    private boolean isStreamDecodingFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("encoding error")
                        || normalized.contains("malformed")
                        || normalized.contains("invalid utf")
                        || normalized.contains("character coding")) {
                    return true;
                }
            }
            String className = current.getClass().getName().toLowerCase(Locale.ROOT);
            if (className.contains("malformedinputexception")
                    || className.contains("charactercodingexception")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<ChatMessage> messagesForModel(List<ChatMessage> messages, List<ToolSpec> visibleTools) {
        if (visibleTools != null && !visibleTools.isEmpty()) {
            return withSystemInstruction(messages, toolProtocolInstruction(visibleTools));
        }
        return withSystemInstruction(messages, noToolAvailableInstruction());
    }

    private List<ChatMessage> withSystemInstruction(List<ChatMessage> messages, String instruction) {
        List<ChatMessage> guarded = new ArrayList<>();
        int insertAt = 0;
        while (insertAt < messages.size() && "system".equals(messages.get(insertAt).role())) {
            guarded.add(messages.get(insertAt));
            insertAt++;
        }
        guarded.add(ChatMessage.system(instruction));
        guarded.addAll(messages.subList(insertAt, messages.size()));
        return guarded;
    }

    private String toolProtocolInstruction(List<ToolSpec> visibleTools) {
        String toolNames = String.join(", ", extractToolNames(visibleTools));
        return """
                Tool output contract for this turn:
                - Available tools: %s.
                - If a tool is needed, use the API structured tool_calls field only.
                - If no tool is needed, answer directly in natural language.
                - Valid text example: "Here is the answer..."
                - Invalid visible text examples: <||DSML||tool_calls>, {"tool_calls":[...]}, <invoke name="search_web">, or "I will search first."
                - Never expose XML, JSON, DSML, invoke tags, function-call syntax, tool ids, or other internal protocol text to the user.
                """.formatted(toolNames);
    }

    private String noToolAvailableInstruction() {
        return """
                No tools are available in this turn.
                Do not claim that you will search, browse, call a tool, or retrieve external information.
                Do not output tool-call markup, XML, JSON, DSML, function-call syntax, or internal protocol text.
                Valid output example: "Here is the answer..."
                Invalid output examples: <||DSML||tool_calls>, {"tool_calls":[...]}, <invoke name="search_web">, or "I will search first."
                Answer the user's question directly in natural language using general knowledge, prior conversation context, preloaded evidence, or tool results already present in the messages.
                If reliable external evidence would be required and no evidence is already present, say so briefly.
                """;
    }

    private ChatResponse retryAfterPseudoToolOutput(HarnessRequest request,
                                                    List<ChatMessage> messages,
                                                    List<ToolSpec> visibleTools,
                                                    Consumer<String> tokenConsumer) {
        List<ChatMessage> retryMessages = new ArrayList<>(messages);
        retryMessages.add(ChatMessage.system("""
                Your previous response attempted to expose an internal tool-call protocol as visible text.
                That is invalid.
                Rewrite the response into exactly one valid output shape:
                1. Natural-language final answer visible to the user.
                2. API structured tool_calls, if tools are provided by this request and a tool is truly needed.
                Do not output XML, JSON, DSML, tool_calls text, invoke tags, function-call syntax, or internal protocol text.
                Invalid examples: <||DSML||tool_calls>, {"tool_calls":[...]}, <invoke name="search_web">, "I will search first."
                If tools are provided by the API request, call them only through structured tool_calls.
                If no tools are provided, answer directly in natural language and do not say you searched or retrieved external information.
                """));
        ChatRequest retryRequest = new ChatRequest(
                request.provider(),
                request.model(),
                List.copyOf(retryMessages),
                request.temperature(),
                request.maxTokens(),
                visibleTools == null || visibleTools.isEmpty() ? null : visibleTools,
                request.apiKey(),
                request.apiUrl(),
                null,
                null,
                request.traceId()
        );
        return callModel(request, retryRequest, "pseudo_tool_retry", tokenConsumer);
    }

    private ChatMessage suppressToolCallPreamble(ChatMessage message) {
        if (message == null || message.toolCalls() == null || message.toolCalls().isEmpty()) {
            return message;
        }
        return new ChatMessage(message.role(), null, message.toolCalls(), message.toolCallId());
    }

    private void appendSkippedToolMessages(List<ChatMessage> messages,
                                           List<ToolCall> toolCalls,
                                           int startIndex,
                                           String errorMessage) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        for (int i = Math.max(0, startIndex); i < toolCalls.size(); i++) {
            ToolCall toolCall = toolCalls.get(i);
            ToolResult result = ToolResult.failure(
                    safeToolCallId(toolCall, i),
                    safeToolName(toolCall),
                    errorMessage
            );
            messages.add(ChatMessage.tool(result.toolCallId(), toToolMessageContent(result)));
        }
    }

    private String safeToolCallId(ToolCall toolCall, int index) {
        if (toolCall != null && StringUtils.hasText(toolCall.id())) {
            return toolCall.id();
        }
        return "tool_call_" + index;
    }

    private String safeToolName(ToolCall toolCall) {
        if (toolCall != null && toolCall.function() != null && StringUtils.hasText(toolCall.function().name())) {
            return toolCall.function().name();
        }
        return "unknown_tool";
    }

    private int maxToolCalls(HarnessRequest request) {
        return request.maxToolCalls() == null ? DEFAULT_MAX_TOOL_CALLS : Math.max(1, request.maxToolCalls());
    }

    private int maxDuplicateToolCalls(HarnessRequest request) {
        return request.maxDuplicateToolCalls() == null
                ? DEFAULT_MAX_DUPLICATE_TOOL_CALLS
                : Math.max(1, request.maxDuplicateToolCalls());
    }

    private boolean deadlineExceeded(LocalDateTime deadlineAt) {
        return deadlineAt != null && LocalDateTime.now().isAfter(deadlineAt);
    }

    private String toolCallSignature(com.yanban.core.model.ToolCall modelToolCall) {
        String name = modelToolCall.function() == null ? "<unknown>" : modelToolCall.function().name();
        String arguments = modelToolCall.function() == null ? "" : modelToolCall.function().arguments();
        return name + ":" + normalizeToolArguments(arguments);
    }

    private String normalizeToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(arguments));
        } catch (Exception ex) {
            return arguments.trim().replaceAll("\\s+", " ");
        }
    }

    private HarnessResult synthesizeFinalAnswerAfterToolBudget(HarnessRequest request,
                                                               List<ChatMessage> messages,
                                                               int steps,
                                                               String originalError,
                                                               Consumer<String> tokenConsumer) {
        List<ChatMessage> finalMessages = new ArrayList<>(messages);
        finalMessages.add(ChatMessage.system("""
                Tool-call budget is exhausted.
                Do not call any more tools.
                Produce the best final answer for the user's current task using the tool results already present in the conversation.
                If evidence is incomplete, state the limitation clearly and provide the most useful partial answer.
                """));
        try {
            ChatRequest chatRequest = new ChatRequest(
                    request.provider(),
                    request.model(),
                    List.copyOf(finalMessages),
                    request.temperature(),
                    request.maxTokens(),
                    null,
                    request.apiKey(),
                    request.apiUrl(),
                    null,
                    null,
                    request.traceId()
            );
            ChatResponse response = callModel(request, chatRequest, "final_synthesis", tokenConsumer);
            ChatMessage assistantMessage = response == null ? null : response.message();
            if (assistantMessage != null) {
                finalMessages.add(assistantMessage);
            }
            List<com.yanban.core.model.ToolCall> toolCalls = assistantMessage == null ? null : assistantMessage.toolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                String content = assistantMessage == null ? null : assistantMessage.content();
                String safeContent = content == null || content.isBlank()
                        ? "Tool-call budget was exhausted, but the model did not produce a final answer. Please narrow the task and retry."
                        : content;
                return HarnessResult.success(safeContent, finalMessages, steps + 1);
            }
            String error = originalError + "; final no-tool synthesis still returned tool_calls";
            log.warn("{} traceId={}", error, request.traceId());
            return HarnessResult.failure(error, finalMessages, steps + 1);
        } catch (Exception ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();
            String error = originalError + "; final synthesis failed: " + message;
            log.warn("Harness final synthesis after tool budget failed provider={} traceId={}",
                    request.provider(),
                    request.traceId(),
                    ex);
            return HarnessResult.failure(error, finalMessages, steps);
        }
    }

    private List<String> extractToolNames(List<ToolSpec> tools) {
        return tools.stream()
                .map(tool -> tool.function() == null ? "<unknown>" : tool.function().name())
                .toList();
    }

    private boolean isPseudoToolOutput(ChatMessage message) {
        return message != null
                && (message.toolCalls() == null || message.toolCalls().isEmpty())
                && (looksLikePseudoToolCall(message.content()) || looksLikeToolSearchPreamble(message.content()));
    }

    private List<ToolCall> parsePseudoToolCalls(String content, List<ToolSpec> visibleTools) {
        if (content == null || content.isBlank() || visibleTools == null || visibleTools.isEmpty()) {
            return List.of();
        }
        Set<String> visibleToolNames = new LinkedHashSet<>(extractToolNames(visibleTools));
        String normalized = normalizePseudoToolProtocol(content);
        Matcher invokeMatcher = DSML_INVOKE_PATTERN.matcher(normalized);
        List<ToolCall> calls = new ArrayList<>();
        int index = 0;
        while (invokeMatcher.find()) {
            String toolName = invokeMatcher.group(1) == null ? "" : invokeMatcher.group(1).trim();
            if (!visibleToolNames.contains(toolName)) {
                continue;
            }
            ObjectNode arguments = objectMapper.createObjectNode();
            Matcher parameterMatcher = DSML_PARAMETER_PATTERN.matcher(invokeMatcher.group(2));
            while (parameterMatcher.find()) {
                putPseudoToolArgument(
                        arguments,
                        parameterMatcher.group(1),
                        parameterMatcher.group(2),
                        parameterMatcher.group(3)
                );
            }
            calls.add(new ToolCall("pseudo_tool_call_" + (++index), "function", new ToolCall.FunctionCall(toolName, arguments.toString())));
        }
        if (!calls.isEmpty()) {
            log.info("Harness converted pseudo tool-call text to structured calls tools={}", calls.stream()
                    .map(call -> call.function() == null ? "<unknown>" : call.function().name())
                    .toList());
        }
        return calls;
    }

    private String normalizePseudoToolProtocol(String content) {
        return content
                .replace('\uff5c', '|')
                .replace('\u201c', '"')
                .replace('\u201d', '"')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'');
    }

    private void putPseudoToolArgument(ObjectNode arguments, String rawName, String stringFlag, String rawValue) {
        if (!StringUtils.hasText(rawName)) {
            return;
        }
        String name = rawName.trim();
        String value = rawValue == null ? "" : rawValue.trim();
        boolean forceString = stringFlag == null || Boolean.parseBoolean(stringFlag);
        if (forceString) {
            arguments.put(name, value);
            return;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            arguments.put(name, Boolean.parseBoolean(value));
            return;
        }
        try {
            arguments.put(name, Integer.parseInt(value));
            return;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        try {
            arguments.put(name, Double.parseDouble(value));
            return;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        arguments.put(name, value);
    }

    private boolean looksLikePseudoToolCall(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = content.toLowerCase();
        String compact = normalized.replaceAll("\\s+", "");
        return compact.contains("<||dsml||tool_calls")
                || compact.contains("</||dsml||tool_calls")
                || compact.contains("||dsml||invoke")
                || normalized.contains("<tool_call>")
                || normalized.contains("</tool_call>")
                || normalized.contains("<tool_calls")
                || normalized.contains("</tool_calls")
                || normalized.contains("<invoke")
                || normalized.contains("invoke name=")
                || normalized.contains("parameter name=")
                || normalized.contains("\"tool_calls\"")
                || normalized.contains("tool_calls>")
                || normalized.contains("tool_calls")
                && (normalized.contains("search_web")
                || normalized.contains("search_knowledge")
                || normalized.contains("search_literature")
                || normalized.contains("function")
                || normalized.contains("invoke"))
                || normalized.contains("<path>")
                || normalized.contains("<arg_key>")
                || normalized.contains("mcp_fs__")
                || normalized.contains("filesystem");
    }

    private boolean looksLikeToolSearchPreamble(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        return normalized.contains("我来为您搜索")
                || normalized.contains("我来搜索")
                || normalized.contains("我将搜索")
                || normalized.contains("让我搜索")
                || normalized.contains("先搜索")
                || normalized.contains("搜索权威")
                || normalized.contains("检索权威")
                || normalized.contains("i will search")
                || normalized.contains("let me search")
                || normalized.contains("i'll search")
                || normalized.contains("search authoritative");
    }

    private boolean shouldReleaseBufferedStream(String pending) {
        if (!StringUtils.hasText(pending)) {
            return false;
        }
        return pending.length() >= STREAM_GUARD_RELEASE_CHARS || pending.contains("\n\n");
    }

    private String abbreviate(String content) {
        if (content == null || content.isBlank()) {
            return "<empty>";
        }
        return content.length() <= TOOL_PROTOCOL_PREVIEW_LIMIT
                ? content
                : content.substring(0, TOOL_PROTOCOL_PREVIEW_LIMIT) + "...";
    }

    private String abbreviate(String content, int limit) {
        if (content == null || content.isBlank()) {
            return "<empty>";
        }
        int safeLimit = Math.max(32, limit);
        return content.length() <= safeLimit
                ? content
                : content.substring(0, safeLimit) + "...";
    }

    private Set<String> resolveAllowedTools(HarnessRequest request) {
        Set<String> allowed = request.allowedToolNames() == null
                ? null
                : new LinkedHashSet<>(request.allowedToolNames());
        if (request.ragDisabled()) {
            if (allowed == null) {
                allowed = new LinkedHashSet<>(toolRegistry.listToolNames());
            }
            allowed.remove("search_knowledge");
        }
        return allowed;
    }

    private boolean requiresCurrentWebEvidence(String userMessage, Set<String> allowedTools) {
        if (!isToolAllowed("search_web", allowedTools) || userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String normalized = userMessage.toLowerCase(Locale.ROOT);
        return CURRENT_INFO_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean isToolAllowed(String toolName, Set<String> allowedTools) {
        if (allowedTools == null) {
            return toolRegistry.find(toolName).isPresent();
        }
        return allowedTools.contains(toolName);
    }

    private Set<String> withoutAllowedTool(Set<String> allowedTools, String toolName) {
        Set<String> updated = allowedTools == null
                ? new LinkedHashSet<>(toolRegistry.listToolNames())
                : new LinkedHashSet<>(allowedTools);
        updated.remove(toolName);
        return updated;
    }

    private ToolResult preloadCurrentWebEvidence(HarnessRequest request, Set<String> allowedTools) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("query", currentWebSearchQuery(request.userMessage()));
        arguments.put("topK", 8);
        com.yanban.core.tool.ToolCall call = new com.yanban.core.tool.ToolCall(
                "preflight_search_web",
                "search_web",
                arguments
        );
        try {
            ToolExecutionContext.setCurrentUserId(request.userId());
            ToolResult result = toolRegistry.execute(call, allowedTools);
            log.info("Harness preloaded current web evidence success={} traceId={}",
                    result.success(),
                    request.traceId());
            return result;
        } catch (RuntimeException ex) {
            String message = blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
            log.warn("Harness current web evidence preload failed traceId={} error={}",
                    request.traceId(),
                    message);
            return ToolResult.failure(call.id(), call.name(), message);
        } finally {
            ToolExecutionContext.clear();
        }
    }

    private String currentWebSearchQuery(String userMessage) {
        String normalized = userMessage == null ? "" : userMessage.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 180) {
            normalized = normalized.substring(0, 180);
        }
        return normalized + " latest official model release 2026";
    }

    private String currentWebEvidenceInstruction() {
        return """
                The user is asking for current, latest, recent, or time-sensitive public information.
                External web evidence is required for time-sensitive claims.
                Use search_web results and any later tool results as evidence. Prefer official vendor pages, release notes, docs, or dated announcements.
                If search results include sourceAuthority fields, prioritize official and primary_technical sources. Do not use secondary/community/media sources as the sole evidence for latest model names, release dates, pricing, or capabilities.
                If only low-authority sources are available, say confidence is limited and recommend checking official vendor pages.
                In the final answer, include source URLs or source names and retrieval limitations.
                If external search is degraded, empty, or does not support a claim, do not present that claim as the latest fact. Say the external evidence was insufficient instead of relying on model memory.
                Do not invent future model names, version numbers, dates, URLs, or vendor releases.
                """;
    }

    private String buildCurrentWebEvidenceContext(ToolResult result) {
        String evidence;
        try {
            if (result != null && result.success() && result.output() != null) {
                evidence = objectMapper.writeValueAsString(result.output());
            } else {
                evidence = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                        .put("success", false)
                        .put("error", result == null
                                ? "missing_search_result"
                                : blankToDefault(result.errorMessage(), "search_web_failed")));
            }
        } catch (Exception ex) {
            evidence = "{\"success\":false,\"error\":\"failed_to_serialize_search_evidence\"}";
        }
        return "Preloaded current web evidence from search_web. Use it as mandatory evidence for time-sensitive claims:\n"
                + abbreviate(evidence, 8000);
    }

    private String buildKnowledgeContext(List<KnowledgeSnippet> snippets) {
        StringBuilder sb = new StringBuilder("""
                Private knowledge-base snippets visible to the current user are listed below.
                Prefer this evidence when answering. Cite snippets with citationId when possible.
                If all scoreBand values are low, say retrieval confidence is limited before using general knowledge.

                """);
        for (int i = 0; i < snippets.size(); i++) {
            KnowledgeSnippet snippet = snippets.get(i);
            sb.append(i + 1)
                    .append(". citationId=")
                    .append(blankToDefault(snippet.citationId(), snippet.filename() + "#" + snippet.chunkIndex()))
                    .append(" source=")
                    .append(blankToDefault(snippet.source(), "knowledge_base"))
                    .append(" filename=")
                    .append(blankToDefault(snippet.filename(), "unknown"))
                    .append(" chunkIndex=")
                    .append(snippet.chunkIndex())
                    .append(" score=")
                    .append(snippet.score())
                    .append(" scoreBand=")
                    .append(blankToDefault(snippet.scoreBand(), "unknown"))
                    .append(" rerankScore=")
                    .append(snippet.rerankScore() == null ? "unknown" : String.format(java.util.Locale.ROOT, "%.3f", snippet.rerankScore()))
                    .append(" rerankReason=")
                    .append(blankToDefault(snippet.rerankReason(), "unknown"))
                    .append("\n")
                    .append(snippet.content())
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    private ToolResult executeTool(com.yanban.core.model.ToolCall modelToolCall, Long userId, Set<String> allowedTools) {
        JsonNode arguments = parseArguments(modelToolCall);
        com.yanban.core.tool.ToolCall toolCall = new com.yanban.core.tool.ToolCall(
                modelToolCall.id(),
                modelToolCall.function().name(),
                arguments
        );
        try {
            ToolExecutionContext.setCurrentUserId(userId);
            return toolRegistry.execute(toolCall, allowedTools);
        } catch (RuntimeException ex) {
            return ToolResult.failure(toolCall.id(), toolCall.name(), ex.getMessage());
        } finally {
            ToolExecutionContext.clear();
        }
    }

    private ToolResult postProcessToolResult(ToolResult result, HarnessRequest request) {
        ToolResult current = result;
        for (ToolResultPostProcessor processor : toolResultPostProcessors) {
            try {
                current = processor.process(current, request);
            } catch (Exception ex) {
                log.warn("Harness tool result post-processor failed tool={} processor={}",
                        current.toolName(),
                        processor.getClass().getSimpleName(),
                        ex);
            }
        }
        return current;
    }

    private JsonNode parseArguments(com.yanban.core.model.ToolCall modelToolCall) {
        String rawArguments = modelToolCall.function() == null ? null : modelToolCall.function().arguments();
        if (rawArguments == null || rawArguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(rawArguments);
        } catch (Exception ex) {
            throw new HarnessException("Failed to parse tool arguments for " + modelToolCall.function().name(), ex);
        }
    }

    private String toToolMessageContent(ToolResult result) {
        try {
            if (result.success()) {
                return objectMapper.writeValueAsString(result.output());
            }
            return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("success", false)
                    .put("error", result.errorMessage()));
        } catch (Exception ex) {
            throw new HarnessException("Failed to serialize tool result", ex);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class StreamingToolCallBuilder {
        private final int index;
        private String id;
        private String type;
        private String functionName;
        private final StringBuilder arguments = new StringBuilder();

        private StreamingToolCallBuilder(int index) {
            this.index = index;
        }

        private void append(ChatChunk.ToolCallDelta delta) {
            if (delta == null) {
                return;
            }
            if (StringUtils.hasText(delta.id())) {
                id = delta.id();
            }
            if (StringUtils.hasText(delta.type())) {
                type = delta.type();
            }
            if (StringUtils.hasText(delta.functionName())) {
                functionName = delta.functionName();
            }
            if (delta.argumentsDelta() != null && !delta.argumentsDelta().isEmpty()) {
                arguments.append(delta.argumentsDelta());
            }
        }

        private ToolCall build() {
            if (!StringUtils.hasText(functionName)) {
                return null;
            }
            String resolvedId = StringUtils.hasText(id) ? id : "tool_call_" + index;
            String resolvedType = StringUtils.hasText(type) ? type : "function";
            return new ToolCall(
                    resolvedId,
                    resolvedType,
                    new ToolCall.FunctionCall(functionName, arguments.toString())
            );
        }
    }
}
