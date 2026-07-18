package com.yanban.core.agent.worker;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.yanban.core.research.ResearchEvidenceRef;
import java.util.List;

/** One concise, evidence-bound observation from a read-only Worker. */
public record WorkerFinding(String findingId, WorkerMaterialType materialType, String summary,
                            @JsonDeserialize(contentUsing = StrictResearchEvidenceRefDeserializer.class)
                            List<ResearchEvidenceRef> evidenceRefs)
        implements RejectsUnknownFields {
    public static final int MAX_SUMMARY_UTF8_BYTES = 8 * 1024;
    public static final int MAX_EVIDENCE_REFS = 128;

    public WorkerFinding {
        findingId = WorkerContractSupport.identifier(findingId, "worker finding id");
        if (materialType == null) {
            throw new IllegalArgumentException("worker finding material type is required");
        }
        summary = WorkerContractSupport.safeText(summary, "worker finding summary",
                MAX_SUMMARY_UTF8_BYTES, false);
        evidenceRefs = WorkerContractSupport.sortedDistinctEvidence(evidenceRefs, MAX_EVIDENCE_REFS,
                "worker finding evidence");
        if (evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("worker finding must be evidence-bound");
        }
    }
}
