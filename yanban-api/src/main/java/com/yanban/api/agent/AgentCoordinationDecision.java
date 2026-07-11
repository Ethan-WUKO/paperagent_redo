package com.yanban.api.agent;

import java.util.Objects;

/** Immutable audit decision made before any runtime adapter is entered. */
public record AgentCoordinationDecision(
        AgentStrategy selectedStrategy,
        boolean explicitPlanRequest,
        boolean degraded,
        AgentStrategy degradedFrom,
        String reason
) {
    public AgentCoordinationDecision {
        Objects.requireNonNull(selectedStrategy, "selectedStrategy must not be null");
        reason = reason == null || reason.isBlank() ? "selected" : reason;
    }
}
