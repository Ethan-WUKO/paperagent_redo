package com.yanban.core.model;

import java.util.List;

public record ChatMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId
) {
    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM.value(), content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER.value(), content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT.value(), content, null, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(ChatRole.TOOL.value(), content, null, toolCallId);
    }
}
