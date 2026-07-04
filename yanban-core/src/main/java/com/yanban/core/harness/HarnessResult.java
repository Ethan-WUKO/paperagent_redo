package com.yanban.core.harness;

import com.yanban.core.model.ChatMessage;
import java.util.List;

public record HarnessResult(
        boolean success,
        String assistantContent,
        List<ChatMessage> messages,
        int steps,
        String errorMessage
) {
    public static HarnessResult success(String assistantContent, List<ChatMessage> messages, int steps) {
        return new HarnessResult(true, assistantContent, List.copyOf(messages), steps, null);
    }

    public static HarnessResult failure(String errorMessage, List<ChatMessage> messages, int steps) {
        return new HarnessResult(false, null, List.copyOf(messages), steps, errorMessage);
    }
}
