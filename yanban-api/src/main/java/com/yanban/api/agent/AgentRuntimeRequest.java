package com.yanban.api.agent;

import com.yanban.core.model.ChatMessage;
import java.util.List;
import java.util.function.Consumer;

public record AgentRuntimeRequest(
        AgentStrategy strategy,
        Long sessionId,
        List<ChatMessage> history,
        Long userId,
        String userMessage,
        String provider,
        String model,
        Double temperature,
        Integer maxTokens,
        int maxSteps,
        boolean ragDisabled,
        String skillId,
        String apiKey,
        String apiUrl,
        String skillPrompt,
        AgentRuntimeMode runtimeMode,
        AgentToolCallingMode toolCallingMode,
        ResolvedToolPolicy toolPolicy,
        Integer maxToolCalls,
        Integer maxDuplicateToolCalls,
        String traceId,
        Consumer<String> tokenConsumer,
        Consumer<String> processConsumer,
        Long planId,
        ProjectRuntimeContext projectContext
) {
    public AgentRuntimeRequest {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        runtimeMode = AgentRuntimeMode.LANGCHAIN4J;
        toolCallingMode = AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING;
        history = history == null ? List.of() : List.copyOf(history);
        if (toolPolicy == null) {
            throw new IllegalArgumentException("toolPolicy must be resolved before runtime execution");
        }
    }

    /** Compatibility bridge for existing callers. A null legacy list is fail-closed, never unrestricted. */
    public AgentRuntimeRequest(
            AgentStrategy strategy, Long sessionId, List<ChatMessage> history, Long userId, String userMessage,
            String provider, String model, Double temperature, Integer maxTokens, int maxSteps, boolean ragDisabled,
            String skillId, String apiKey, String apiUrl, String skillPrompt, AgentRuntimeMode runtimeMode,
            AgentToolCallingMode toolCallingMode, List<String> allowedToolNames, Integer maxToolCalls,
            Integer maxDuplicateToolCalls, String traceId, Consumer<String> tokenConsumer,
            Consumer<String> processConsumer) {
        this(strategy, sessionId, history, userId, userMessage, provider, model, temperature, maxTokens, maxSteps,
                ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode, toolCallingMode,
                new ResolvedToolPolicy(allowedToolNames == null ? List.of() : allowedToolNames,
                        maxToolCalls == null ? 0 : maxToolCalls,
                        maxDuplicateToolCalls == null ? 0 : maxDuplicateToolCalls,
                "legacy_runtime_request"),
                maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer, null, null);
    }

    /** Source-compatible bridge for callers that already supplied a resolved policy. */
    public AgentRuntimeRequest(
            AgentStrategy strategy, Long sessionId, List<ChatMessage> history, Long userId, String userMessage,
            String provider, String model, Double temperature, Integer maxTokens, int maxSteps, boolean ragDisabled,
            String skillId, String apiKey, String apiUrl, String skillPrompt, AgentRuntimeMode runtimeMode,
            AgentToolCallingMode toolCallingMode, ResolvedToolPolicy toolPolicy, Integer maxToolCalls,
            Integer maxDuplicateToolCalls, String traceId, Consumer<String> tokenConsumer,
            Consumer<String> processConsumer) {
        this(strategy, sessionId, history, userId, userMessage, provider, model, temperature, maxTokens, maxSteps,
                ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode, toolCallingMode, toolPolicy,
                maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer, null, null);
    }

    public List<String> allowedToolNames() {
        return toolPolicy.allowedTools();
    }

    /** Only the Coordinator may attach the execution strategy for a CHAT request. */
    public AgentRuntimeRequest withStrategy(AgentStrategy selectedStrategy) {
        if (selectedStrategy == null) {
            throw new IllegalArgumentException("selectedStrategy must not be null");
        }
        return new AgentRuntimeRequest(selectedStrategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, projectContext);
    }

    /** Attach server-owned plan identity after a Plan API request has been authorized. */
    public AgentRuntimeRequest withPlanId(Long planId) {
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, projectContext);
    }

    /** Only an authenticated Project API adapter may attach this context. */
    public AgentRuntimeRequest withProjectContext(ProjectRuntimeContext context) {
        if (context == null || !context.userId().equals(userId)) {
            throw new IllegalArgumentException("project context must match the runtime user");
        }
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, context);
    }

    /**
     * Adds a server-owned instruction for a bounded repair turn. The user task,
     * authenticated Project identity, resolved tool policy and endpoint remain unchanged.
     */
    public AgentRuntimeRequest withAdditionalSystemInstruction(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return this;
        }
        java.util.ArrayList<ChatMessage> augmentedHistory = new java.util.ArrayList<>(history);
        augmentedHistory.add(ChatMessage.system(instruction));
        return new AgentRuntimeRequest(strategy, sessionId, augmentedHistory, userId, userMessage, provider, model,
                temperature, maxTokens, maxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, toolPolicy, maxToolCalls, maxDuplicateToolCalls, traceId, tokenConsumer, processConsumer,
                planId, projectContext);
    }

    /** Bounded reflection may only reduce runtime and tool budgets. */
    public AgentRuntimeRequest withReducedBudget(int reducedMaxSteps, int reducedMaxToolCalls) {
        if (reducedMaxSteps <= 0 || reducedMaxSteps > maxSteps || reducedMaxToolCalls < 0
                || reducedMaxToolCalls > toolPolicy.maxToolCalls()) {
            throw new IllegalArgumentException("reflection may only use remaining runtime budget");
        }
        ResolvedToolPolicy reducedPolicy = new ResolvedToolPolicy(toolPolicy.allowedTools(), reducedMaxToolCalls,
                Math.min(toolPolicy.maxDuplicateToolCalls(), reducedMaxToolCalls), toolPolicy.reason());
        return new AgentRuntimeRequest(strategy, sessionId, history, userId, userMessage, provider, model,
                temperature, maxTokens, reducedMaxSteps, ragDisabled, skillId, apiKey, apiUrl, skillPrompt, runtimeMode,
                toolCallingMode, reducedPolicy, reducedMaxToolCalls, reducedPolicy.maxDuplicateToolCalls(), traceId,
                tokenConsumer, processConsumer, planId, projectContext);
    }
}
