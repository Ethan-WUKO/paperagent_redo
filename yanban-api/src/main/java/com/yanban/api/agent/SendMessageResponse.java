package com.yanban.api.agent;

import java.util.List;

public record SendMessageResponse(
        boolean success,
        String assistantContent,
        int steps,
        String errorMessage,
        String navigationUrl,
        List<AgentMessageResponse> messages,
        AgentDebugPayload debug,
        List<ProjectEvidenceResponse> projectEvidence
) {
    public SendMessageResponse(boolean success, String assistantContent, int steps, String errorMessage,
                               String navigationUrl, List<AgentMessageResponse> messages, AgentDebugPayload debug) {
        this(success, assistantContent, steps, errorMessage, navigationUrl, messages, debug, List.of());
    }
}
