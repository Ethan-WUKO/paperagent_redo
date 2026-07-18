package com.yanban.core.agent.worker;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.yanban.core.research.ResearchEvidenceRef;
import java.util.Comparator;
import java.util.List;

/** Authority-free structured comparison across one or more material kinds. */
public record CrossMaterialDifference(String differenceId, Status status,
                                      List<WorkerMaterialType> materialTypes,
                                      List<String> observationSummaries,
                                      List<String> sourceWorkerTaskIds,
                                      @JsonDeserialize(contentUsing = StrictResearchEvidenceRefDeserializer.class)
                                      List<ResearchEvidenceRef> evidenceRefs)
        implements RejectsUnknownFields {
    public enum Status { UNRESOLVED }

    public static final int MAX_MATERIAL_TYPES = WorkerMaterialType.values().length;
    public static final int MAX_OBSERVATION_SUMMARIES = 128;
    public static final int MAX_OBSERVATION_UTF8_BYTES = 8 * 1024;
    public static final int MAX_SOURCE_TASKS = 64;
    public static final int MAX_EVIDENCE_REFS = 512;

    public CrossMaterialDifference {
        differenceId = WorkerContractSupport.identifier(differenceId, "cross-material difference id");
        if (status == null || materialTypes == null || materialTypes.isEmpty()
                || materialTypes.size() > MAX_MATERIAL_TYPES
                || materialTypes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("cross-material difference status and material types are required");
        }
        materialTypes = List.copyOf(materialTypes.stream().distinct().sorted().toList());
        observationSummaries = WorkerContractSupport.sortedDistinctTexts(observationSummaries,
                MAX_OBSERVATION_SUMMARIES, MAX_OBSERVATION_UTF8_BYTES,
                "cross-material observation summary", true);
        if (sourceWorkerTaskIds == null || sourceWorkerTaskIds.isEmpty()
                || sourceWorkerTaskIds.size() > MAX_SOURCE_TASKS
                || sourceWorkerTaskIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("cross-material source task ids are required");
        }
        sourceWorkerTaskIds = List.copyOf(sourceWorkerTaskIds.stream()
                .map(value -> WorkerContractSupport.identifier(value, "source worker task id"))
                .distinct().sorted().toList());
        evidenceRefs = WorkerContractSupport.sortedDistinctEvidence(evidenceRefs, MAX_EVIDENCE_REFS,
                "cross-material difference evidence");
        if (evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("cross-material difference must be evidence-bound");
        }
    }

    static Comparator<CrossMaterialDifference> deterministicOrder() {
        return Comparator.comparing(CrossMaterialDifference::differenceId)
                .thenComparing(CrossMaterialDifference::status)
                .thenComparing(difference -> difference.materialTypes().toString())
                .thenComparing(difference -> difference.observationSummaries().toString())
                .thenComparing(difference -> difference.sourceWorkerTaskIds().toString())
                .thenComparing(difference -> WorkerContractSupport.evidenceKey(difference.evidenceRefs()));
    }
}
