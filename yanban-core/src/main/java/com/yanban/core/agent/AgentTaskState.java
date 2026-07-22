package com.yanban.core.agent;

/**
 * MVP-0 shared task-state projection. It does not alter the legacy task table.
 */
public record AgentTaskState(AgentTaskStatus status, AgentTaskPhase phase, AgentTaskOutcome outcome) {
    public AgentTaskState {
        if (status == null || phase == null) {
            throw new IllegalArgumentException("status and phase must not be null");
        }
        if (AgentTaskStatus.isTerminal(status.name())) {
            if (outcome == null || phase != AgentTaskPhase.FINALIZING) {
                throw new IllegalArgumentException("terminal task states require a finalizing phase and outcome");
            }
            if (!matchesTerminalOutcome(status, outcome)) {
                throw new IllegalArgumentException("task status does not match outcome");
            }
        } else {
            if (outcome != null) {
                throw new IllegalArgumentException("active task states must not have an outcome");
            }
            if (!allowsActivePhase(status, phase)) {
                throw new IllegalArgumentException("task status does not allow phase " + phase);
            }
        }
    }

    public static AgentTaskState active(AgentTaskStatus status, AgentTaskPhase phase) {
        return new AgentTaskState(status, phase, null);
    }

    public static AgentTaskState completed(AgentTaskOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        AgentTaskStatus status = switch (outcome) {
            case SUCCEEDED, PARTIAL -> AgentTaskStatus.COMPLETED;
            case FAILED -> AgentTaskStatus.FAILED;
            case CANCELLED -> AgentTaskStatus.CANCELLED;
            case TIMED_OUT -> AgentTaskStatus.FAILED;
        };
        return new AgentTaskState(status, AgentTaskPhase.FINALIZING, outcome);
    }

    private static boolean matchesTerminalOutcome(AgentTaskStatus status, AgentTaskOutcome outcome) {
        return switch (status) {
            case COMPLETED -> outcome == AgentTaskOutcome.SUCCEEDED || outcome == AgentTaskOutcome.PARTIAL;
            case FAILED -> outcome == AgentTaskOutcome.FAILED || outcome == AgentTaskOutcome.TIMED_OUT;
            case CANCELLED, STOPPED -> outcome == AgentTaskOutcome.CANCELLED;
            default -> false;
        };
    }

    private static boolean allowsActivePhase(AgentTaskStatus status, AgentTaskPhase phase) {
        return switch (status) {
            case PENDING -> phase == AgentTaskPhase.CREATED || phase == AgentTaskPhase.ROUTING
                    || phase == AgentTaskPhase.CONTEXT_PREPARING || phase == AgentTaskPhase.PLANNING;
            case RUNNING -> phase == AgentTaskPhase.ROUTING || phase == AgentTaskPhase.CONTEXT_PREPARING
                    || phase == AgentTaskPhase.PLANNING || phase == AgentTaskPhase.EXECUTING
                    || phase == AgentTaskPhase.WAITING_TOOL || phase == AgentTaskPhase.RESUMING
                    || phase == AgentTaskPhase.VERIFYING;
            case WAITING_INPUT -> phase == AgentTaskPhase.WAITING_INPUT;
            case PAUSED -> phase == AgentTaskPhase.PAUSED;
            case CANCEL_REQUESTED, CANCELLING -> phase == AgentTaskPhase.FINALIZING;
            default -> false;
        };
    }
}
