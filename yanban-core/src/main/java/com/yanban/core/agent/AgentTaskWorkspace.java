package com.yanban.core.agent;

import java.util.List;

/** Bounded workspace projection owned by the canonical run identity. */
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
        boolean l0 = "L0_REQUEST_BOUND".equals(persistenceLevel)
                && !checkpointAvailable && !restartResumable;
        boolean l1 = "L1_PERSISTED".equals(persistenceLevel)
                && !checkpointAvailable && !restartResumable
                && "AGENT_PLAN".equals(identity.source());
        boolean l2 = "L2_DURABLE".equals(persistenceLevel)
                && checkpointAvailable && restartResumable
                && "AGENT_PLAN".equals(identity.source()) && identity.projectId() != null;
        if (!l0 && !l1 && !l2) {
            throw new IllegalArgumentException("Task Workspace persistence capability does not match its canonical run");
        }
        droppedItemCount = Math.max(0, droppedItemCount);
    }

    private static List<String> copy(List<String> values) { return values == null ? List.of() : List.copyOf(values); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
