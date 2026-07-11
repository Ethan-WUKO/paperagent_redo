package com.yanban.api.agent;

import java.util.List;

public record AgentExperimentRequest(
        Boolean enabled,
        AgentRuntimeMode runtimeMode,
        AgentRagMode ragMode,
        AgentMemoryMode memoryMode,
        AgentToolCallingMode toolCallingMode,
        List<AgentDebugFlag> debugFlags,
        Boolean persistEvalRecord
) {
    public AgentExperimentRequest {
        debugFlags = debugFlags == null ? List.of() : List.copyOf(debugFlags);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
