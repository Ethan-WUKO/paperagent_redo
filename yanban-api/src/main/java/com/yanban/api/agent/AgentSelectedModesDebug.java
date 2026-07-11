package com.yanban.api.agent;

public record AgentSelectedModesDebug(
        AgentRuntimeMode runtimeMode,
        AgentRagMode ragMode,
        AgentMemoryMode memoryMode,
        AgentToolCallingMode toolCallingMode
) {
}
