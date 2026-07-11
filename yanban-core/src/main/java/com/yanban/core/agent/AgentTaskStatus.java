package com.yanban.core.agent;

import java.util.Set;

public enum AgentTaskStatus {
    PENDING,
    RUNNING,
    WAITING_INPUT,
    CANCEL_REQUESTED,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED,
    STOPPED;

    private static final Set<AgentTaskStatus> TERMINAL_STATUSES = Set.of(COMPLETED, FAILED, CANCELLED, STOPPED);
    private static final Set<AgentTaskStatus> CANCELLING_STATUSES = Set.of(CANCEL_REQUESTED, CANCELLING);

    public String value() {
        return name();
    }

    public static boolean isTerminal(String status) {
        AgentTaskStatus parsed = parse(status);
        return parsed != null && TERMINAL_STATUSES.contains(parsed);
    }

    public static boolean isCancelling(String status) {
        AgentTaskStatus parsed = parse(status);
        return parsed != null && CANCELLING_STATUSES.contains(parsed);
    }

    public static boolean canCancel(String status) {
        return !isTerminal(status) && !isCancelling(status);
    }

    private static AgentTaskStatus parse(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AgentTaskStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
