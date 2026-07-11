package com.yanban.api.agent;

import java.util.Objects;

/** Structured outcome of a synchronous Coordinator invocation. */
public record AgentCoordinationResult(
        AgentCoordinationDecision decision,
        AgentRuntimeResult runtimeResult
) {
    public AgentCoordinationResult {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(runtimeResult, "runtimeResult must not be null");
    }
}
