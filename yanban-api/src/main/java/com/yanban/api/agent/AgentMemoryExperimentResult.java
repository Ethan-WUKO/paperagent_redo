package com.yanban.api.agent;

public record AgentMemoryExperimentResult(
        AgentContextPackage contextPackage,
        AgentMemoryWindowDebug memoryWindow
) {
    public AgentMemoryExperimentResult {
        if (contextPackage == null) {
            throw new IllegalArgumentException("contextPackage must not be null");
        }
    }
}
