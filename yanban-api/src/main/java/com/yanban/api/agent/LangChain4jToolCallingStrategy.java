package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.tool.ToolDescriptor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LangChain4jToolCallingStrategy {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jToolCallingStrategy.class);
    private static final int PROJECT_TOOL_EXTENSION_BATCH = 4;
    private static final int PROJECT_TOOL_HARD_LIMIT = 24;
    private static final Set<String> ASYNC_NON_TERMINAL_STATES = Set.of("RUNNING", "WAITING");
    private static final Set<String> ASYNC_TERMINAL_STATES = Set.of(
            "COMPLETED", "DONE", "SUCCEEDED", "FAILED", "ERROR", "CANCELLED", "STOPPED");
    private static final String TOOL_ROUTING_SYSTEM_PROMPT = """
            You may decide whether to answer directly or call tools.
            Prefer a tool from the current tool specifications over guessing when evidence is needed.
            Never invent a tool name or request a tool that is absent from the current tool specifications.
            If no tool is needed, answer directly and concisely.
            """;
    private static final String PROJECT_READ_SYSTEM_PROMPT = """
            This request is bound to an authenticated read-only Project. Use only the Project tools
            exposed for this request and only Project-relative paths. The server supplies Project identity;
            never send or guess projectId in tool arguments. project_manifest provides safe
            inventory metadata, but it is not file-content evidence. Prefer a specialized Project research
            tool when its purpose matches the request; its typed evidence is current file-content evidence.
            Otherwise, before making Project conclusions, capture at least one relevant current observation
            with project_read_file or project_search. Follow each tool's JSON schema exactly, especially
            array-valued relativePaths fields.
            To inspect all applicable files, call project_manifest, select its concrete relative paths, and
            pass those paths to the specialized tool. Never use ".", "*", "**", or "/" as a file path.
            For project_cross_material_search on a large or unfamiliar Project, first use project_manifest,
            pass selected concrete files through relativePaths, and normally keep maxMatches between 10 and 20.
            After an input/result byte-budget error, narrow relativePaths instead of repeating a whole-Project search.
            For a directory overview, use the manifest to locate candidates, then inspect relevant files
            and clearly distinguish path-based inference from file-content findings. Never claim a complete
            review beyond the observations retrieved in this turn.

            Treat typed research-tool items as the source of truth. Before stating any numeric count, derive
            it from the matching typed items and verify that it equals the entries you enumerate. If the count
            and enumeration cannot be reconciled, omit the numeric total and report the observed entries only.

            Return one coherent final answer rather than separate answers or concatenated drafts. When using
            Markdown, keep every heading to a short standalone phrase on its own line, put a space after the
            heading marker (for example, "## Purpose"), and start the explanatory prose on a new line below it.
            Never format a complete sentence or paragraph as a heading. Use a hyphen followed by a space ("- ")
            for unordered list items.
            """;
    private static final String TOOL_BUDGET_FINAL_ANSWER_PROMPT = """
            The tool-call budget has been reached. Do not call any more tools.
            Use only the conversation and tool results already available to produce the best final answer.
            If evidence is incomplete, say that briefly and explain what can be concluded from the retrieved results.
            """;
    private static final String NO_PROGRESS_FINAL_ANSWER_PROMPT = """
            Every tool request in the latest assistant step duplicated an earlier invocation, so no new
            tool execution was performed. Do not call any more tools. Use the tool results already present
            in the conversation to produce the best final answer. If the evidence is incomplete, state the
            limitation briefly instead of repeating a tool call.
            """;
    private final LangChain4jChatModelAdapter chatModel;
    private final LangChain4jToolProvider toolProvider;
    private final ObjectMapper objectMapper;

    public LangChain4jToolCallingStrategy(LangChain4jChatModelAdapter chatModel,
                                          LangChain4jToolProvider toolProvider,
                                          ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
    }

    public boolean supports(AgentRuntimeRequest request) {
        return request != null && request.toolCallingMode() == AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING;
    }

    public AgentRuntimeResult run(AgentRuntimeRequest request) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>(toLangChainMessages(request.history()));
        messages.add(SystemMessage.from(TOOL_ROUTING_SYSTEM_PROMPT));
        if (request.projectContext() != null) {
            messages.add(SystemMessage.from(PROJECT_READ_SYSTEM_PROMPT));
        }
        if (StringUtils.hasText(request.skillPrompt())) {
            messages.add(SystemMessage.from(request.skillPrompt()));
        }
        messages.add(UserMessage.from(request.userMessage()));

        List<String> toolTrace = new ArrayList<>();
        List<String> fallbacks = new ArrayList<>();
        Map<String, InvocationState> invocationStates = new LinkedHashMap<>();
        Set<String> verifiedObservations = new LinkedHashSet<>();
        Set<String> allowedTools = new LinkedHashSet<>(request.toolPolicy().allowedTools());
        ToolProviderResult toolProviderResult = toolProvider.provideTools(request);
        List<ToolSpecification> toolSpecifications = new ArrayList<>(toolProviderResult.tools().keySet());
        TokenUsage totalUsage = null;
        int toolCalls = 0;
        int effectiveToolLimit = request.toolPolicy().maxToolCalls();
        int successfulCallsSinceExtension = 0;
        boolean adaptiveProjectBudget = request.projectContext() != null
                && request.toolPolicy().maxToolCalls() >= 12;
        log.info("LangChain4j tool run start sessionId={} userId={} model={} allowedTools={} maxSteps={} maxToolCalls={} maxDuplicateToolCalls={}",
                request.sessionId(),
                request.userId(),
                request.model(),
                List.copyOf(allowedTools),
                request.maxSteps(),
                request.toolPolicy().maxToolCalls(),
                request.toolPolicy().maxDuplicateToolCalls());
        emitProcess(request, "正在分析问题，并判断是否需要调用工具。");

        for (int step = 0; step < request.maxSteps(); step++) {
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .parameters(ChatRequestParameters.builder()
                            .modelName(request.model())
                            .temperature(request.temperature())
                            .maxOutputTokens(request.maxTokens())
                            .toolSpecifications(toolSpecifications)
                            .build())
                    .build();
            dev.langchain4j.model.chat.response.ChatResponse response = callModel(chatRequest, request);
            totalUsage = TokenUsage.sum(totalUsage, response == null ? null : response.tokenUsage());
            AiMessage aiMessage = response == null ? null : response.aiMessage();
            if (aiMessage == null) {
                fallbacks.add("langchain4j_empty_response");
                return failure(messages, toolTrace, fallbacks, step + 1, totalUsage, "LangChain4j returned an empty response");
            }
            messages.add(aiMessage);
            log.info("LangChain4j step={} assistantPreview={} toolRequests={}",
                    step + 1,
                    abbreviate(aiMessage.text()),
                    summarizeToolRequests(aiMessage.toolExecutionRequests()));

            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            if (requests == null || requests.isEmpty()) {
                String content = aiMessage.text();
                log.info("LangChain4j completed without tool call step={} assistantPreview={}",
                        step + 1,
                        abbreviate(content));
                emitProcess(request, toolCalls > 0 ? "工具结果已整理完成，正在生成最终回答。" : "已判断无需调用工具，正在生成最终回答。");
                if (!isStreaming(request)) {
                    emitToken(request, content);
                }
                return success(messages, toolTrace, fallbacks, step + 1, totalUsage, content);
            }

            emitProcess(request, "已决定调用 " + requests.size() + " 个工具：" + summarizeToolNames(requests));
            int newToolCallsThisStep = 0;
            int reusedToolCallsThisStep = 0;
            for (int i = 0; i < requests.size(); i++) {
                ToolExecutionRequest toolRequest = requests.get(i);
                String signature = toolRequest.name() + "|" + normalizeArguments(toolRequest.arguments());
                InvocationState previous = invocationStates.get(signature);
                String repeatError = repeatError(toolRequest, previous, request.toolPolicy().maxDuplicateToolCalls());
                if (repeatError != null) {
                    String reason = repeatError + ": " + signature;
                    String reusedContent = reusedToolResult(previous, reason);
                    fallbacks.add("Tool result reused without re-execution: " + reason);
                    toolTrace.add(buildReusedToolTraceLine(step + 1, toolRequest, previous, reason));
                    messages.add(ToolExecutionResultMessage.from(
                            toolRequest.id(),
                            toolRequest.name(),
                            reusedContent
                    ));
                    reusedToolCallsThisStep++;
                    log.info("LangChain4j tool result reused step={} tool={} args={} priorSuccess={} originalToolCallId={} reason={}",
                            step + 1,
                            toolRequest.name(),
                            abbreviate(defaultString(toolRequest.arguments(), "{}")),
                            previous.outcome().success(),
                            previous.sourceToolCallId(),
                            repeatError);
                    continue;
                }
                if (toolCalls >= effectiveToolLimit && adaptiveProjectBudget
                        && successfulCallsSinceExtension > 0 && effectiveToolLimit < PROJECT_TOOL_HARD_LIMIT) {
                    int previousLimit = effectiveToolLimit;
                    effectiveToolLimit = Math.min(PROJECT_TOOL_HARD_LIMIT,
                            effectiveToolLimit + PROJECT_TOOL_EXTENSION_BATCH);
                    successfulCallsSinceExtension = 0;
                    fallbacks.add("Project tool budget extended after verified progress: "
                            + previousLimit + "->" + effectiveToolLimit);
                    log.info("Project tool budget extended sessionId={} previousLimit={} effectiveLimit={} hardLimit={}",
                            request.sessionId(), previousLimit, effectiveToolLimit, PROJECT_TOOL_HARD_LIMIT);
                }
                if (toolCalls >= effectiveToolLimit) {
                    String error = adaptiveProjectBudget
                            ? "Tool-call budget exceeded: effectiveMaxToolCalls=" + effectiveToolLimit
                                    + ", hardMaxToolCalls=" + PROJECT_TOOL_HARD_LIMIT
                            : "Tool-call budget exceeded: maxToolCalls=" + effectiveToolLimit;
                    fallbacks.add(error);
                    addSkippedToolResults(messages, toolTrace, requests, i, step + 1, error);
                    return finalAnswerWithoutMoreTools(messages, toolTrace, fallbacks, step + 1, totalUsage, request, error)
                            .withRuntimeStopSignal(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);
                }
                toolCalls++;
                newToolCallsThisStep++;

                emitProcess(request, "正在调用工具：" + toolRequest.name());
                ToolExecutionOutcome toolResult = executeTool(toolProviderResult, toolRequest, request.userId(), allowedTools);
                if (toolResult.scopeRefinementRequired()) {
                    toolCalls--;
                    fallbacks.add("Tool scope refinement required; execution budget was not consumed: "
                            + toolRequest.name());
                }
                invocationStates.put(signature, InvocationState.after(previous, toolRequest.id(), toolResult));
                if (toolResult.success() && !toolResult.scopeRefinementRequired()
                        && registerVerifiedNewObservation(toolResult.content(), verifiedObservations)) {
                    successfulCallsSinceExtension++;
                }
                log.info("LangChain4j tool step={} tool={} args={} success={} error={}",
                        step + 1,
                        toolRequest.name(),
                        abbreviate(defaultString(toolRequest.arguments(), "{}")),
                        toolResult.success(),
                        toolResult.success() ? null : abbreviate(defaultString(toolResult.errorMessage(), "tool_failed")));
                toolTrace.add(buildToolTraceLine(step + 1, toolRequest, toolResult));
                emitProcess(request, toolResult.success()
                        ? "工具调用完成：" + toolRequest.name()
                        : "工具调用失败：" + toolRequest.name() + "，" + defaultString(toolResult.errorMessage(), "tool_failed"));
                messages.add(ToolExecutionResultMessage.from(
                        toolRequest.id(),
                        toolRequest.name(),
                        toolResult.content()
                ));
            }
            if (newToolCallsThisStep == 0 && reusedToolCallsThisStep > 0) {
                String reason = "No new tool progress: all " + reusedToolCallsThisStep
                        + " requested invocation(s) reused earlier results";
                fallbacks.add(reason);
                log.info("LangChain4j terminating repeated-tool loop step={} reusedToolCalls={} toolCalls={}",
                        step + 1, reusedToolCallsThisStep, toolCalls);
                return finalAnswerAfterNoProgress(
                        messages, toolTrace, fallbacks, step + 1, totalUsage, request, reason);
            }
        }

        String reason = "Tool reasoning rounds reached maxSteps=" + request.maxSteps();
        fallbacks.add(reason);
        return finalAnswerWithoutMoreTools(messages, toolTrace, fallbacks, request.maxSteps(), totalUsage, request, reason)
                .withRuntimeStopSignal(AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED);
    }

    private AgentRuntimeResult finalAnswerWithoutMoreTools(List<dev.langchain4j.data.message.ChatMessage> messages,
                                                           List<String> toolTrace,
                                                           List<String> fallbacks,
                                                           int steps,
                                                           TokenUsage usage,
                                                           AgentRuntimeRequest request,
                                                           String reason) {
        emitProcess(request, "Tool-call budget reached. Generating the final answer from existing results.");
        messages.add(SystemMessage.from(TOOL_BUDGET_FINAL_ANSWER_PROMPT));
        ChatRequest finalRequest = ChatRequest.builder()
                .messages(messages)
                .parameters(ChatRequestParameters.builder()
                        .modelName(request.model())
                        .temperature(request.temperature())
                        .maxOutputTokens(request.maxTokens())
                        .build())
                .build();
        long successfulCalls = toolTrace.stream().filter(trace -> trace != null && trace.contains("success=true")).count();
        long failedCalls = toolTrace.stream().filter(trace -> trace != null && trace.contains("success=false")).count();
        log.info("LangChain4j final synthesis start sessionId={} steps={} successfulTools={} failedTools={} reason={}",
                request.sessionId(), steps, successfulCalls, failedCalls, reason);
        dev.langchain4j.model.chat.response.ChatResponse response;
        try {
            response = callModel(finalRequest, request);
        } catch (RuntimeException ex) {
            return synthesisTransportPartial(messages, toolTrace, fallbacks, steps, usage, request, reason, ex);
        }
        TokenUsage totalUsage = TokenUsage.sum(usage, response == null ? null : response.tokenUsage());
        AiMessage aiMessage = response == null ? null : response.aiMessage();
        if (aiMessage == null || !StringUtils.hasText(aiMessage.text())) {
            log.warn("LangChain4j final synthesis empty sessionId={} steps={} reason={}",
                    request.sessionId(), steps, reason);
            return failure(messages, toolTrace, fallbacks, steps, totalUsage,
                    "LangChain4j returned an empty final response after " + reason);
        }
        messages.add(aiMessage);
        if (!isStreaming(request)) {
            emitToken(request, aiMessage.text());
        }
        log.info("LangChain4j final synthesis completed sessionId={} steps={} contentLength={} reason={}",
                request.sessionId(), steps, aiMessage.text().length(), reason);
        return success(messages, toolTrace, fallbacks, steps, totalUsage, aiMessage.text());
    }

    private AgentRuntimeResult synthesisTransportPartial(
            List<dev.langchain4j.data.message.ChatMessage> messages,
            List<String> toolTrace,
            List<String> fallbacks,
            int steps,
            TokenUsage usage,
            AgentRuntimeRequest request,
            String reason,
            RuntimeException exception) {
        String diagnostic = "Final synthesis unavailable after controlled stop: "
                + abbreviate(defaultString(exception.getMessage(), exception.getClass().getSimpleName()));
        fallbacks.add(diagnostic);
        long successfulCalls = toolTrace.stream().filter(trace -> trace != null && trace.contains("success=true")).count();
        long failedCalls = toolTrace.stream().filter(trace -> trace != null && trace.contains("success=false")).count();
        String content = "The tool phase stopped in a controlled manner after collecting available Project evidence. "
                + "The final model synthesis timed out, so this step is only partially complete. "
                + "Successful tool observations: " + successfulCalls + ", failed or skipped observations: " + failedCalls
                + ". Limitation: " + reason;
        messages.add(AiMessage.from(content));
        if (!isStreaming(request)) {
            emitToken(request, content);
        }
        log.warn("LangChain4j final synthesis transport failed after controlled stop sessionId={} steps={} successfulTools={} failedTools={} reason={}",
                request.sessionId(), steps, successfulCalls, failedCalls, reason, exception);
        return success(messages, toolTrace, fallbacks, steps, usage, content);
    }

    private AgentRuntimeResult finalAnswerAfterNoProgress(List<dev.langchain4j.data.message.ChatMessage> messages,
                                                          List<String> toolTrace,
                                                          List<String> fallbacks,
                                                          int steps,
                                                          TokenUsage usage,
                                                          AgentRuntimeRequest request,
                                                          String reason) {
        emitProcess(request, "No new tool progress. Generating the final answer from existing results.");
        messages.add(SystemMessage.from(NO_PROGRESS_FINAL_ANSWER_PROMPT));
        ChatRequest finalRequest = ChatRequest.builder()
                .messages(messages)
                .parameters(ChatRequestParameters.builder()
                        .modelName(request.model())
                        .temperature(request.temperature())
                        .maxOutputTokens(request.maxTokens())
                        .build())
                .build();
        dev.langchain4j.model.chat.response.ChatResponse response = callModel(finalRequest, request);
        TokenUsage totalUsage = TokenUsage.sum(usage, response == null ? null : response.tokenUsage());
        AiMessage aiMessage = response == null ? null : response.aiMessage();
        if (aiMessage == null || !StringUtils.hasText(aiMessage.text())) {
            return failure(messages, toolTrace, fallbacks, steps, totalUsage,
                    "LangChain4j returned an empty final response after " + reason);
        }
        messages.add(aiMessage);
        if (!isStreaming(request)) {
            emitToken(request, aiMessage.text());
        }
        return success(messages, toolTrace, fallbacks, steps, totalUsage, aiMessage.text());
    }

    private void addSkippedToolResults(List<dev.langchain4j.data.message.ChatMessage> messages,
                                       List<String> toolTrace,
                                       List<ToolExecutionRequest> requests,
                                       int fromIndex,
                                       int step,
                                       String reason) {
        for (int i = fromIndex; i < requests.size(); i++) {
            ToolExecutionRequest request = requests.get(i);
            messages.add(ToolExecutionResultMessage.from(
                    request.id(),
                    request.name(),
                    budgetSkippedToolResult(reason)
            ));
            toolTrace.add("step=" + step
                    + " tool=" + request.name()
                    + " args=" + defaultString(request.arguments(), "{}")
                    + " success=false error=" + reason);
        }
    }

    private String budgetSkippedToolResult(String reason) {
        var result = objectMapper.createObjectNode();
        result.put("success", false);
        result.put("executed", false);
        result.put("skipped", true);
        result.put("controlledStop", true);
        result.put("errorCode", AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED.name());
        result.put("stopReason", AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED.name());
        result.put("outcome", CompletionStatus.PARTIAL.name());
        result.put("retryable", false);
        result.put("message", reason);
        return result.toString();
    }

    private dev.langchain4j.model.chat.response.ChatResponse callModel(ChatRequest chatRequest, AgentRuntimeRequest request) {
        if (!isStreaming(request)) {
            return chatModel.chat(chatRequest, request);
        }
        return streamModel(chatRequest, request);
    }

    private boolean isStreaming(AgentRuntimeRequest request) {
        return request != null && request.tokenConsumer() != null;
    }

    private dev.langchain4j.model.chat.response.ChatResponse streamModel(ChatRequest chatRequest, AgentRuntimeRequest request) {
        StreamAccumulator accumulator = new StreamAccumulator(request);
        chatModel.stream(chatRequest, request)
                .toIterable()
                .forEach(accumulator::accept);
        AiMessage aiMessage = accumulator.toAiMessage();
        return dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(aiMessage)
                .modelName(chatRequest == null ? null : chatRequest.modelName())
                .build();
    }

    private ToolExecutionOutcome executeTool(ToolProviderResult providerResult, ToolExecutionRequest toolRequest,
                                             Long userId, Set<String> allowedTools) {
        try {
            if (!allowedTools.contains(toolRequest.name())) {
                return outcome(false,
                        toolProvider.failureContent("tool_permission_denied: " + toolRequest.name()),
                        "tool_permission_denied");
            }
            dev.langchain4j.service.tool.ToolExecutor executor = providerResult.toolExecutorByName(toolRequest.name());
            if (executor == null) {
                return outcome(false, toolProvider.failureContent("tool_not_found: " + toolRequest.name()), "tool_not_found");
            }
            String content = executor.execute(toolRequest, userId);
            return classifyToolContent(content);
        } catch (Exception ex) {
            String error = defaultString(ex.getMessage(), ex.getClass().getSimpleName());
            return outcome(false, toolProvider.failureContent(error), error);
        }
    }

    private ToolExecutionOutcome classifyToolContent(String content) {
        if (!StringUtils.hasText(content)) {
            return outcome(true, content, null);
        }
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node.isObject() && node.has("success") && !node.path("success").asBoolean(true)) {
                String error = node.path("errorMessage").asText(node.path("error").asText("tool_failed"));
            return outcome(false, content, error, node.path("errorCode").asText(""));
            }
        } catch (Exception ignored) {
            // Text and domain-specific JSON without the ToolResult success field remain successful payloads.
        }
        return outcome(true, content, null, null);
    }

    private ToolExecutionOutcome outcome(boolean success, String content, String errorMessage) {
        return outcome(success, content, errorMessage, null);
    }

    private ToolExecutionOutcome outcome(boolean success, String content, String errorMessage, String errorCode) {
        AsyncObservation async = asyncObservation(content);
        return new ToolExecutionOutcome(success, content, errorMessage, errorCode, async.observed(), async.terminal());
    }

    private AsyncObservation asyncObservation(String content) {
        if (!StringUtils.hasText(content)) {
            return new AsyncObservation(false, false);
        }
        try {
            JsonNode node = objectMapper.readTree(content);
            String state = node.path("status").asText(node.path("state").asText(""));
            if (!StringUtils.hasText(state)) {
                return new AsyncObservation(false, false);
            }
            String normalized = state.trim().toUpperCase(Locale.ROOT);
            if (ASYNC_TERMINAL_STATES.contains(normalized)) {
                return new AsyncObservation(true, true);
            }
            return new AsyncObservation(ASYNC_NON_TERMINAL_STATES.contains(normalized), false);
        } catch (Exception ignored) {
            return new AsyncObservation(false, false);
        }
    }

    private String buildToolTraceLine(int step, ToolExecutionRequest toolRequest, ToolExecutionOutcome toolResult) {
        return "step=" + step
                + " tool=" + toolRequest.name()
                + " args=" + defaultString(toolRequest.arguments(), "{}")
                + " success=" + toolResult.success()
                + (toolResult.success() ? summarizeToolObservation(toolResult.content())
                : " error=" + defaultString(toolResult.errorMessage(), "tool_failed"));
    }

    private String summarizeToolObservation(String content) {
        if (!StringUtils.hasText(content)) return "";
        try {
            JsonNode result = objectMapper.readTree(content);
            if (result.path("hits").isArray()) return " observation=hits:" + result.path("hits").size();
            if (result.path("items").isArray()) return " observation=items:" + result.path("items").size()
                    + (result.has("status") ? ",status:" + result.path("status").asText() : "");
            if (result.has("status")) return " observation=status:" + result.path("status").asText();
        } catch (Exception ignored) {
            // Tool content remains available in the transcript; trace summaries are best effort only.
        }
        return "";
    }

    private boolean registerVerifiedNewObservation(String content, Set<String> observed) {
        if (!StringUtils.hasText(content)) return false;
        try {
            JsonNode result = objectMapper.readTree(content);
            boolean added = registerArrayEntries(result.path("items"), "item:", observed);
            return registerArrayEntries(result.path("evidenceRefs"), "evidence:", observed) || added;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean registerArrayEntries(JsonNode values, String prefix, Set<String> observed) {
        if (!values.isArray() || values.isEmpty()) return false;
        boolean added = false;
        for (JsonNode value : values) {
            added |= observed.add(prefix + value.toString());
        }
        return added;
    }

    private String reusedToolResult(InvocationState previous, String reason) {
        var reused = objectMapper.createObjectNode();
        reused.put("success", previous.outcome().success());
        reused.put("reused", true);
        reused.put("originalToolCallId", defaultString(previous.sourceToolCallId(), "unknown"));
        reused.put("reason", reason);
        reused.put("message", "Identical request was not re-executed; use the original tool result already present.");
        if (!previous.outcome().success()) {
            reused.put("errorMessage", defaultString(previous.outcome().errorMessage(), "tool_failed"));
        }
        return reused.toString();
    }

    private String buildReusedToolTraceLine(int step,
                                            ToolExecutionRequest toolRequest,
                                            InvocationState previous,
                                            String reason) {
        return "step=" + step
                + " tool=" + toolRequest.name()
                + " args=" + defaultString(toolRequest.arguments(), "{}")
                + " success=" + previous.outcome().success()
                + " reused=true originalToolCallId=" + defaultString(previous.sourceToolCallId(), "unknown")
                + " reason=" + reason
                + (previous.outcome().success() ? ""
                : " error=" + defaultString(previous.outcome().errorMessage(), "tool_failed"));
    }

    private List<dev.langchain4j.data.message.ChatMessage> toLangChainMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<dev.langchain4j.data.message.ChatMessage> converted = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            String role = message.role() == null ? "" : message.role().trim().toLowerCase(Locale.ROOT);
            switch (role) {
                case "system" -> converted.add(SystemMessage.from(defaultString(message.content())));
                case "assistant" -> {
                    if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
                        converted.add(AiMessage.from(defaultString(message.content())));
                    } else {
                        List<ToolExecutionRequest> requests = message.toolCalls().stream()
                                .filter(call -> call.function() != null)
                                .map(call -> ToolExecutionRequest.builder()
                                        .id(call.id())
                                        .name(call.function().name())
                                        .arguments(defaultString(call.function().arguments(), "{}"))
                                        .build())
                                .toList();
                        converted.add(AiMessage.from(defaultString(message.content()), requests));
                    }
                }
                case "tool" -> converted.add(ToolExecutionResultMessage.from(
                        defaultString(message.toolCallId(), "tool-call"),
                        "tool",
                        defaultString(message.content())
                ));
                default -> converted.add(UserMessage.from(defaultString(message.content())));
            }
        }
        return converted;
    }

    private AgentRuntimeResult success(List<dev.langchain4j.data.message.ChatMessage> messages,
                                       List<String> toolTrace,
                                       List<String> fallbacks,
                                       int steps,
                                       TokenUsage usage,
                                       String assistantContent) {
        return new AgentRuntimeResult(
                true,
                assistantContent,
                toCoreMessages(messages),
                steps,
                null,
                toolTrace,
                fallbacks,
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(),
                usage == null ? null : usage.totalTokenCount()
        );
    }

    private AgentRuntimeResult failure(List<dev.langchain4j.data.message.ChatMessage> messages,
                                       List<String> toolTrace,
                                       List<String> fallbacks,
                                       int steps,
                                       TokenUsage usage,
                                       String errorMessage) {
        return new AgentRuntimeResult(
                false,
                null,
                toCoreMessages(messages),
                steps,
                errorMessage,
                toolTrace,
                fallbacks,
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(),
                usage == null ? null : usage.totalTokenCount()
        );
    }

    private List<ChatMessage> toCoreMessages(List<dev.langchain4j.data.message.ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> converted = new ArrayList<>();
        for (dev.langchain4j.data.message.ChatMessage message : messages) {
            if (message instanceof UserMessage userMessage) {
                converted.add(ChatMessage.user(userMessage.singleText()));
            } else if (message instanceof SystemMessage systemMessage) {
                converted.add(ChatMessage.system(systemMessage.text()));
            } else if (message instanceof ToolExecutionResultMessage toolMessage) {
                converted.add(ChatMessage.tool(toolMessage.id(), toolMessage.text()));
            } else if (message instanceof AiMessage aiMessage) {
                List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                converted.add(new ChatMessage(
                        "assistant",
                        aiMessage.text(),
                        requests == null || requests.isEmpty() ? null : requests.stream()
                                .map(request -> new com.yanban.core.model.ToolCall(
                                        request.id(),
                                        "function",
                                        new com.yanban.core.model.ToolCall.FunctionCall(request.name(), request.arguments())
                                ))
                                .toList(),
                        null
                ));
            }
        }
        return converted;
    }

    private void emitToken(AgentRuntimeRequest request, String assistantContent) {
        if (request.tokenConsumer() != null && StringUtils.hasText(assistantContent)) {
            request.tokenConsumer().accept(assistantContent);
        }
    }

    private void emitProcess(AgentRuntimeRequest request, String content) {
        if (request.processConsumer() != null && StringUtils.hasText(content)) {
            request.processConsumer().accept(content);
        }
    }

    private String normalizeArguments(String value) {
        String raw = defaultString(value, "{}");
        try {
            return objectMapper.writeValueAsString(canonicalize(objectMapper.readTree(raw)));
        } catch (Exception ignored) {
            // Invalid JSON has no semantic equivalence. Preserve its exact bytes as a stable fallback.
            return "invalid-json:" + raw;
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            var canonical = objectMapper.createArrayNode();
            node.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        var canonical = objectMapper.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.stream().sorted().forEach(name -> canonical.set(name, canonicalize(node.get(name))));
        return canonical;
    }

    private String repeatError(ToolExecutionRequest request, InvocationState previous, int maxDuplicates) {
        if (previous == null) {
            return null;
        }
        ToolDescriptor descriptor = toolProvider.descriptor(request.name()).orElse(null);
        if (descriptor == null) {
            return "Tool metadata missing";
        }
        return switch (descriptor.repeatPolicy()) {
            case DENY_SAME_INPUT -> "Duplicate tool call blocked";
            case POLL_UNTIL_TERMINAL -> {
                if (descriptor.asyncMode() == ToolDescriptor.AsyncMode.SYNC || !previous.asyncStateObserved()) {
                    yield "Repeated asynchronous poll has no observable non-terminal state";
                }
                yield previous.asyncTerminal() ? "Repeated asynchronous poll after terminal state" : null;
            }
            case ALLOW_LIMITED -> {
                if (previous.success()) {
                    yield "Duplicate idempotent retry requires a prior failure";
                }
                if (descriptor.idempotencyPolicy() == ToolDescriptor.IdempotencyPolicy.NONE
                        || !hasIdempotencyKey(request.arguments())) {
                    yield "Duplicate idempotent retry requires an idempotency key";
                }
                yield previous.count() > maxDuplicates ? "Duplicate tool call blocked" : null;
            }
        };
    }

    private boolean hasIdempotencyKey(String arguments) {
        try {
            JsonNode idempotencyKey = objectMapper.readTree(defaultString(arguments, "{}"))
                    .path("clientRequestId");
            return idempotencyKey.isTextual() && StringUtils.hasText(idempotencyKey.asText());
        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> summarizeToolRequests(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        return requests.stream()
                .map(request -> request.name() + "(" + abbreviate(defaultString(request.arguments(), "{}")) + ")")
                .toList();
    }

    private String summarizeToolNames(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return "无";
        }
        return String.join("、", requests.stream()
                .map(ToolExecutionRequest::name)
                .filter(StringUtils::hasText)
                .toList());
    }

    private String abbreviate(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private String defaultString(String value) {
        return defaultString(value, "");
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static final class StreamAccumulator {

        private final AgentRuntimeRequest request;
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
        private StreamMode mode = StreamMode.UNDECIDED;

        private StreamAccumulator(AgentRuntimeRequest request) {
            this.request = request;
        }

        private void accept(ChatChunk chunk) {
            if (chunk == null) {
                return;
            }
            if (!chunk.toolCallDeltas().isEmpty()) {
                mode = StreamMode.TOOL_CALL;
                for (ChatChunk.ToolCallDelta delta : chunk.toolCallDeltas()) {
                    toolCalls.computeIfAbsent(delta.index(), ignored -> new ToolCallBuilder())
                            .append(delta);
                }
                return;
            }
            if (StringUtils.hasText(chunk.content()) && mode != StreamMode.TOOL_CALL) {
                mode = StreamMode.TEXT;
                content.append(chunk.content());
                if (request.tokenConsumer() != null) {
                    request.tokenConsumer().accept(chunk.content());
                }
            }
        }

        private AiMessage toAiMessage() {
            List<ToolExecutionRequest> requests = toolCalls.values().stream()
                    .map(ToolCallBuilder::build)
                    .filter(toolRequest -> StringUtils.hasText(toolRequest.name()))
                    .toList();
            if (!requests.isEmpty()) {
                return AiMessage.from(content.toString(), requests);
            }
            return AiMessage.from(content.toString());
        }
    }

    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private void append(ChatChunk.ToolCallDelta delta) {
            if (delta == null) {
                return;
            }
            if (StringUtils.hasText(delta.id())) {
                id = delta.id();
            }
            if (StringUtils.hasText(delta.functionName())) {
                name = delta.functionName();
            }
            if (delta.argumentsDelta() != null) {
                arguments.append(delta.argumentsDelta());
            }
        }

        private ToolExecutionRequest build() {
            return ToolExecutionRequest.builder()
                    .id(StringUtils.hasText(id) ? id : "tool-call-" + Math.abs(arguments.toString().hashCode()))
                    .name(name)
                    .arguments(arguments.isEmpty() ? "{}" : arguments.toString())
                    .build();
        }
    }

    private enum StreamMode {
        UNDECIDED,
        TEXT,
        TOOL_CALL
    }

    private record InvocationState(int count, String sourceToolCallId, ToolExecutionOutcome outcome) {
        private static InvocationState after(InvocationState previous,
                                             String sourceToolCallId,
                                             ToolExecutionOutcome outcome) {
            return new InvocationState(previous == null ? 1 : previous.count() + 1, sourceToolCallId, outcome);
        }

        private boolean success() {
            return outcome.success();
        }

        private boolean asyncStateObserved() {
            return outcome.asyncStateObserved();
        }

        private boolean asyncTerminal() {
            return outcome.asyncTerminal();
        }
    }

    private record ToolExecutionOutcome(boolean success, String content, String errorMessage, String errorCode,
                                        boolean asyncStateObserved, boolean asyncTerminal) {
        private boolean scopeRefinementRequired() {
            return !success && "VALIDATION_ERROR".equals(errorCode)
                    && errorMessage != null && errorMessage.contains("requires relativePaths");
        }
    }

    private record AsyncObservation(boolean observed, boolean terminal) {
    }
}
