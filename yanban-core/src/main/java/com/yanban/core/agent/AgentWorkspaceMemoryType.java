package com.yanban.core.agent;

/** Auditable short-term memory categories. Internal reasoning is deliberately absent. */
public enum AgentWorkspaceMemoryType {
    TRUSTED_EVIDENCE,
    TOOL_OBSERVATION,
    FAILURE_RESULT,
    CANDIDATE_REFERENCE,
    ARTIFACT_REFERENCE,
    AUDIT_SUMMARY
}
