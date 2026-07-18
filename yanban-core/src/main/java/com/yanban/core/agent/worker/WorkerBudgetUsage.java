package com.yanban.core.agent.worker;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yanban.core.research.ResearchBudgetUsage;

/** Exact usage projection carried by a WorkerResult. */
public record WorkerBudgetUsage(int inputPaths, int toolCalls, int findings, int evidenceRefs,
                                long bytesInspected, long summaryUtf8Bytes)
        implements RejectsUnknownFields {
    public WorkerBudgetUsage {
        if (inputPaths < 0 || toolCalls < 0 || findings < 0 || evidenceRefs < 0
                || bytesInspected < 0 || summaryUtf8Bytes < 0) {
            throw new IllegalArgumentException("worker budget usage must not be negative");
        }
    }

    @JsonIgnore
    public ResearchBudgetUsage researchUsage() {
        return new ResearchBudgetUsage(inputPaths, findings, evidenceRefs, bytesInspected);
    }
}
