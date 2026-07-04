package com.yanban.core.harness;

import com.yanban.core.model.ChatMessage;
import java.time.LocalDateTime;
import java.util.List;

public record HarnessRequest(
        List<ChatMessage> history,
        Long userId,
        String userMessage,
        String provider,
        String model,
        Double temperature,
        Integer maxTokens,
        int maxSteps,
        boolean ragDisabled,
        String apiKey,
        String apiUrl,
        String skillPrompt,
        List<String> allowedToolNames,
        Integer maxToolCalls,
        Integer maxDuplicateToolCalls,
        LocalDateTime deadlineAt,
        String traceId
) {
    public HarnessRequest(List<ChatMessage> history,
                          Long userId,
                          String userMessage,
                          String provider,
                          String model,
                          Double temperature,
                          Integer maxTokens,
                          int maxSteps,
                          boolean ragDisabled,
                          String apiKey,
                          String skillPrompt,
                          List<String> allowedToolNames) {
        this(history, userId, userMessage, provider, model, temperature, maxTokens, maxSteps, ragDisabled,
                apiKey, null, skillPrompt, allowedToolNames, null, null, null, null);
    }

    public HarnessRequest(List<ChatMessage> history,
                          Long userId,
                          String userMessage,
                          String provider,
                          String model,
                          Double temperature,
                          Integer maxTokens,
                          int maxSteps,
                          boolean ragDisabled,
                          String apiKey,
                          String apiUrl,
                          String skillPrompt,
                          List<String> allowedToolNames) {
        this(history, userId, userMessage, provider, model, temperature, maxTokens, maxSteps, ragDisabled,
                apiKey, apiUrl, skillPrompt, allowedToolNames, null, null, null, null);
    }

    public HarnessRequest {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        history = history == null ? List.of() : List.copyOf(history);
    }
}
