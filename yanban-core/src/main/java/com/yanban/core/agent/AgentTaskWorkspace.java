package com.yanban.core.agent;

import java.util.List;

/** Migration-free L0 workspace projection owned by the canonical run identity. */
public record AgentTaskWorkspace(
        AgentRunIdentity identity,
        AgentTaskState state,
        String currentGoal,
        List<String> successConditions,
        List<String> planReferences,
        List<String> observedStepSummaries,
        List<String> remainingWork,
        List<AgentWorkspaceMemoryItem> memory,
        String sessionSummary,
        String canonicalAnswer,
        String persistenceLevel,
        boolean checkpointAvailable,
        boolean restartResumable,
        int droppedItemCount) {
    public AgentTaskWorkspace {
        if (identity == null || state == null || currentGoal == null || currentGoal.isBlank()) {
            throw new IllegalArgumentException("workspace requires canonical identity, state and goal");
        }
        successConditions = copy(successConditions);
        planReferences = copy(planReferences);
        observedStepSummaries = copy(observedStepSummaries);
        remainingWork = copy(remainingWork);
        memory = memory == null ? List.of() : List.copyOf(memory);
        sessionSummary = blankToNull(sessionSummary);
        canonicalAnswer = blankToNull(canonicalAnswer);
        persistenceLevel = persistenceLevel == null ? "L0_REQUEST_BOUND" : persistenceLevel;
        if (!"L0_REQUEST_BOUND".equals(persistenceLevel) || checkpointAvailable || restartResumable) {
            throw new IllegalArgumentException("Task Workspace is L0 request-bound and cannot claim checkpoint or restart capability");
        }
        droppedItemCount = Math.max(0, droppedItemCount);
    }

    private static List<String> copy(List<String> values) { return values == null ? List.of() : List.copyOf(values); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
