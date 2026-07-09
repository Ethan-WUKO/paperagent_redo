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
        ModelSourceDebug modelSource
) {
    public AgentDebugPayload {
        retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
        debugFlags = debugFlags == null ? List.of() : List.copyOf(debugFlags);
        toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        finalCitations = finalCitations == null ? List.of() : List.copyOf(finalCitations);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
    }
}
