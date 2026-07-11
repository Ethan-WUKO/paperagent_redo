package com.yanban.api.agent;

import java.util.List;

public record AgentExperimentContext(
        AgentExperimentRequest request,
        AgentSelectedModesDebug selectedModes,
        AgentRagExperimentResult ragResult
) {
    public AgentExperimentContext {
        if (selectedModes == null) {
            throw new IllegalArgumentException("selectedModes must not be null");
        }
    }

    public boolean enabled() {
        return request != null && request.isEnabled();
    }

    public List<AgentDebugFlag> debugFlags() {
        return request == null ? List.of() : request.debugFlags();
    }

    public boolean hasFlag(AgentDebugFlag flag) {
        return debugFlags().contains(flag);
    }

    public boolean overridesRag() {
        return enabled() && ragResult != null;
    }

    public boolean persistEvalRecord() {
        return request != null && Boolean.TRUE.equals(request.persistEvalRecord());
    }
}
