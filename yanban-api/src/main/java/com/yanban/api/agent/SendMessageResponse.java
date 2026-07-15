package com.yanban.api.agent;

import com.yanban.api.agent.sandbox.CandidateArtifactResponse;

import java.util.List;

public record SendMessageResponse(
        boolean success,
        String assistantContent,
        int steps,
        String errorMessage,
        String navigationUrl,
        List<AgentMessageResponse> messages,
        AgentDebugPayload debug,
        List<ProjectEvidenceResponse> projectEvidence,
        CompletionStatus completionStatus,
        AgentStopReason stopReason,
        String outcome,
        CandidateArtifactResponse candidateArtifact
) {
    public SendMessageResponse(boolean success, String assistantContent, int steps, String errorMessage,
                               String navigationUrl, List<AgentMessageResponse> messages, AgentDebugPayload debug,
                               List<ProjectEvidenceResponse> projectEvidence, CompletionStatus completionStatus,
                               AgentStopReason stopReason, String outcome) {
        this(success, assistantContent, steps, errorMessage, navigationUrl, messages, debug, projectEvidence,
                completionStatus, stopReason, outcome, null);
    }

    public SendMessageResponse(boolean success, String assistantContent, int steps, String errorMessage,
                               String navigationUrl, List<AgentMessageResponse> messages, AgentDebugPayload debug,
                               List<ProjectEvidenceResponse> projectEvidence) {
        this(success, assistantContent, steps, errorMessage, navigationUrl, messages, debug, projectEvidence,
                null, null, null, null);
    }

    public SendMessageResponse(boolean success, String assistantContent, int steps, String errorMessage,
                               String navigationUrl, List<AgentMessageResponse> messages, AgentDebugPayload debug) {
        this(success, assistantContent, steps, errorMessage, navigationUrl, messages, debug, List.of());
    }
}
