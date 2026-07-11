package com.yanban.api.agent;

import java.util.List;

public record AgentRagExperimentResult(
        String ragContext,
        List<AgentRetrievedChunkDebug> retrievedChunks
) {
    public AgentRagExperimentResult {
        retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
    }
}
