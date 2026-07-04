package com.yanban.api.agent;

import java.util.List;

public record SendMessageResponse(
        boolean success,
        String assistantContent,
        int steps,
        String errorMessage,
        String navigationUrl,
        List<AgentMessageResponse> messages
) {
}
