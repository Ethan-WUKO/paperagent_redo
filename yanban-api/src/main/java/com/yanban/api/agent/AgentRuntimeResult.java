package com.yanban.api.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.CandidateIntent;
import com.yanban.core.model.ChatMessage;
import java.util.List;

public record AgentRuntimeResult(
        boolean success,
        String assistantContent,
        List<ChatMessage> messages,
        int steps,
        String errorMessage,
        List<String> toolTrace,
        List<String> fallbacks,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        AgentStrategy selectedStrategy,
        AgentStopReason stopReason,
        String outcome,
        boolean degraded,
        AgentStrategy degradedFrom,
        AgentRuntimeStopSignal runtimeStopSignal,
        Long planId,
        EvidenceLedger evidenceLedger,
        EvidenceLedger trustedEvidenceLedger,
        CompletionVerification completionVerification,
        CandidateChangeSet candidateChangeSet,
        @JsonIgnore CandidateIntent candidateIntent,
        CandidateArtifactResponse candidateArtifact
) {
    public AgentRuntimeResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
        toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
        runtimeStopSignal = runtimeStopSignal == null ? AgentRuntimeStopSignal.NONE : runtimeStopSignal;
        evidenceLedger = evidenceLedger == null ? EvidenceLedger.empty() : evidenceLedger;
        trustedEvidenceLedger = trustedEvidenceLedger == null ? EvidenceLedger.empty() : trustedEvidenceLedger;
    }

    /** Source-compatible result constructor for adapters that do not coordinate themselves. */
    public AgentRuntimeResult(boolean success, String assistantContent, List<ChatMessage> messages, int steps,
                              String errorMessage, List<String> toolTrace, List<String> fallbacks,
                              Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, null, null, null, false, null, AgentRuntimeStopSignal.NONE, null, EvidenceLedger.empty(), EvidenceLedger.empty(), null, null);
    }

    /** Source-compatible canonical projection used before structured Candidate intents existed. */
    public AgentRuntimeResult(boolean success, String assistantContent, List<ChatMessage> messages, int steps,
                              String errorMessage, List<String> toolTrace, List<String> fallbacks,
                              Integer promptTokens, Integer completionTokens, Integer totalTokens,
                              AgentStrategy selectedStrategy, AgentStopReason stopReason, String outcome,
                              boolean degraded, AgentStrategy degradedFrom,
                              AgentRuntimeStopSignal runtimeStopSignal, Long planId,
                              EvidenceLedger evidenceLedger, EvidenceLedger trustedEvidenceLedger,
                              CompletionVerification completionVerification, CandidateChangeSet candidateChangeSet) {
        this(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded,
                degradedFrom, runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger,
                completionVerification, candidateChangeSet, null, null);
    }

    public AgentRuntimeResult withCoordination(AgentStrategy selectedStrategy, AgentStopReason stopReason,
                                               String outcome, boolean degraded, AgentStrategy degradedFrom) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal == null ? AgentRuntimeStopSignal.NONE : runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact);
    }

    public AgentRuntimeResult withRuntimeStopSignal(AgentRuntimeStopSignal signal) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                signal == null ? AgentRuntimeStopSignal.NONE : signal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact);
    }

    public AgentRuntimeResult withPlanId(Long planId) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact);
    }

    public AgentRuntimeResult withEvidenceLedger(EvidenceLedger ledger) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, ledger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact);
    }

    public AgentRuntimeResult insufficientProjectEvidence(EvidenceLedger ledger) {
        return insufficientProjectEvidence(ledger, 0);
    }

    public AgentRuntimeResult insufficientProjectEvidence(EvidenceLedger ledger, int historySize) {
        String limitation = "Insufficient Project evidence: no authorized file read/search observation was captured; this is not a complete review.";
        java.util.ArrayList<ChatMessage> safeMessages = new java.util.ArrayList<>();
        int boundary = Math.max(0, Math.min(historySize, messages.size()));
        safeMessages.addAll(messages.subList(0, boundary));
        for (ChatMessage message : messages.subList(boundary, messages.size())) {
            boolean unverifiedVisibleAssistant = message != null && "assistant".equalsIgnoreCase(message.role())
                    && (message.toolCalls() == null || message.toolCalls().isEmpty());
            if (!unverifiedVisibleAssistant) {
                safeMessages.add(message);
            }
        }
        safeMessages.add(ChatMessage.assistant(limitation));
        return new AgentRuntimeResult(false, limitation, safeMessages, steps, limitation, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, AgentStopReason.PLAN_PARTIAL, "INSUFFICIENT_EVIDENCE",
                true, degradedFrom, runtimeStopSignal, planId, ledger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact);
    }

    public AgentRuntimeResult withCompletionVerification(CompletionVerification verification) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, verification, candidateChangeSet, candidateIntent, candidateArtifact);
    }

    public AgentRuntimeResult withCandidateChangeSet(CandidateChangeSet candidate) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidate, candidateIntent, candidateArtifact);
    }

    public AgentRuntimeResult withCandidateIntent(CandidateIntent intent) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification,
                candidateChangeSet, intent, candidateArtifact);
    }

    public AgentRuntimeResult withCandidateArtifact(CandidateArtifactResponse artifact) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification,
                null, null, artifact);
    }

    public AgentRuntimeResult asVerifiedFailure(String message) {
        return new AgentRuntimeResult(false, assistantContent, messages, steps, message, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact);
    }

    /** A controlled partial has useful chat-visible content and is delivered normally without claiming verification. */
    public AgentRuntimeResult asControlledPartial() {
        return new AgentRuntimeResult(true, assistantContent, messages, steps, null, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, trustedEvidenceLedger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact);
    }

    public AgentRuntimeResult withTrustedEvidenceLedger(EvidenceLedger ledger) {
        return new AgentRuntimeResult(success, assistantContent, messages, steps, errorMessage, toolTrace, fallbacks,
                promptTokens, completionTokens, totalTokens, selectedStrategy, stopReason, outcome, degraded, degradedFrom,
                runtimeStopSignal, planId, evidenceLedger, ledger, completionVerification, candidateChangeSet, candidateIntent, candidateArtifact);
    }
}
