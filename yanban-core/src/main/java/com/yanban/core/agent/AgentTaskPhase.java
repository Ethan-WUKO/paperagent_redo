package com.yanban.core.agent;

/** Stable runtime phase vocabulary; persistence remains on the existing current_stage field in MVP-0. */
public enum AgentTaskPhase {
    CREATED,
    PLANNING,
    EXECUTING,
    WAITING_INPUT,
    VERIFYING,
    FINALIZING
}
