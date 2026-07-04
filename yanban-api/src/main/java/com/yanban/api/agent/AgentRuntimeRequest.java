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
        List<String> allowedToolNames,
        Integer maxToolCalls,
        Integer maxDuplicateToolCalls,
        String traceId,
        Consumer<String> tokenConsumer
) {
    public AgentRuntimeRequest {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        history = history == null ? List.of() : List.copyOf(history);
    }
}
