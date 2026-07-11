package com.yanban.api.agent;

import java.util.List;

public record AgentDebugPayload(
        AgentSelectedModesDebug selectedModes,
        List<AgentRetrievedChunkDebug> retrievedChunks,
        String injectedContext,
        String rawPrompt,
        List<String> debugFlags,
        List<String> toolTrace,
        List<String> finalCitations,
        AgentExperimentMetricsDebug metrics,
        AgentMemoryWindowDebug memoryWindow,
        List<String> fallbacks,
        ModelSourceDebug modelSource,
        CandidateChangeSet candidateChangeSet
) {
    public AgentDebugPayload {
        retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
        debugFlags = debugFlags == null ? List.of() : List.copyOf(debugFlags);
        toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        finalCitations = finalCitations == null ? List.of() : List.copyOf(finalCitations);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
    }

    /** Source-compatible constructor for existing debug payload producers. */
    public AgentDebugPayload(AgentSelectedModesDebug selectedModes,
                             List<AgentRetrievedChunkDebug> retrievedChunks,
                             String injectedContext, String rawPrompt, List<String> debugFlags,
                             List<String> toolTrace, List<String> finalCitations,
                             AgentExperimentMetricsDebug metrics, AgentMemoryWindowDebug memoryWindow,
                             List<String> fallbacks, ModelSourceDebug modelSource) {
        this(selectedModes, retrievedChunks, injectedContext, rawPrompt, debugFlags, toolTrace, finalCitations,
                metrics, memoryWindow, fallbacks, modelSource, null);
    }
}
