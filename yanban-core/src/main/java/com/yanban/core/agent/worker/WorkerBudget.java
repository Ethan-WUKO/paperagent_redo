package com.yanban.core.agent.worker;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yanban.core.research.ResearchBudget;

/** Bounded resource ceiling for one read-only Worker task. */
public record WorkerBudget(int maxInputPaths, int maxToolCalls, int maxFindings,
                           int maxEvidenceRefs, long maxBytesInspected, long maxSummaryUtf8Bytes)
        implements RejectsUnknownFields {
    public static final int HARD_MAX_INPUT_PATHS = 256;
    public static final int HARD_MAX_TOOL_CALLS = 128;
    public static final int HARD_MAX_FINDINGS = 512;
    public static final int HARD_MAX_EVIDENCE_REFS = 1_024;
    public static final long HARD_MAX_BYTES_INSPECTED = 64L * 1024 * 1024;
    public static final long HARD_MAX_SUMMARY_UTF8_BYTES = 1024L * 1024;

    public WorkerBudget {
        if (maxInputPaths < 1 || maxToolCalls < 1 || maxFindings < 1 || maxEvidenceRefs < 1
                || maxBytesInspected < 1 || maxSummaryUtf8Bytes < 1) {
            throw new IllegalArgumentException("worker budget limits must be positive");
        }
        if (maxInputPaths > HARD_MAX_INPUT_PATHS || maxToolCalls > HARD_MAX_TOOL_CALLS
                || maxFindings > HARD_MAX_FINDINGS || maxEvidenceRefs > HARD_MAX_EVIDENCE_REFS
                || maxBytesInspected > HARD_MAX_BYTES_INSPECTED
                || maxSummaryUtf8Bytes > HARD_MAX_SUMMARY_UTF8_BYTES) {
            throw new IllegalArgumentException("worker budget exceeds the contract hard limit");
        }
    }

    public boolean fitsWithin(WorkerBudget parent) {
        return parent != null && maxInputPaths <= parent.maxInputPaths
                && maxToolCalls <= parent.maxToolCalls && maxFindings <= parent.maxFindings
                && maxEvidenceRefs <= parent.maxEvidenceRefs
                && maxBytesInspected <= parent.maxBytesInspected
                && maxSummaryUtf8Bytes <= parent.maxSummaryUtf8Bytes;
    }

    public void validate(WorkerBudgetUsage usage) {
        if (usage == null || usage.inputPaths() > maxInputPaths || usage.toolCalls() > maxToolCalls
                || usage.findings() > maxFindings || usage.evidenceRefs() > maxEvidenceRefs
                || usage.bytesInspected() > maxBytesInspected
                || usage.summaryUtf8Bytes() > maxSummaryUtf8Bytes) {
            throw new IllegalArgumentException("worker result exceeds its declared budget");
        }
        researchBudget().validate(usage.researchUsage());
    }

    @JsonIgnore
    public ResearchBudget researchBudget() {
        return new ResearchBudget(maxInputPaths, maxFindings, maxEvidenceRefs, maxBytesInspected);
    }
}
