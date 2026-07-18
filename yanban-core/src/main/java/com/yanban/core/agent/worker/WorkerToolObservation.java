package com.yanban.core.agent.worker;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.yanban.core.research.ResearchEvidenceRef;
import java.util.List;

/** Summary of one allowed read-only tool observation; never raw tool authority. */
public record WorkerToolObservation(String toolName, String summary,
                                    @JsonDeserialize(contentUsing = StrictResearchEvidenceRefDeserializer.class)
                                    List<ResearchEvidenceRef> evidenceRefs)
        implements RejectsUnknownFields {
    public static final int MAX_SUMMARY_UTF8_BYTES = 8 * 1024;
    public static final int MAX_EVIDENCE_REFS = 128;

    public WorkerToolObservation {
        List<String> validated = WorkerContractSupport.sortedDistinctTools(List.of(toolName), 1);
        toolName = validated.get(0);
        summary = WorkerContractSupport.safeText(summary, "worker tool observation summary",
                MAX_SUMMARY_UTF8_BYTES, false);
        evidenceRefs = WorkerContractSupport.sortedDistinctEvidence(evidenceRefs, MAX_EVIDENCE_REFS,
                "worker tool observation evidence");
    }
}
