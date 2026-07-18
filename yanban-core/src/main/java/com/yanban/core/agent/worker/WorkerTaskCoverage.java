package com.yanban.core.agent.worker;

import java.util.List;

/** Bounded audit projection of one source task's execution and material coverage. */
public record WorkerTaskCoverage(String workerTaskId, WorkerResult.Status executionStatus,
                                 CoverageStatus coverageStatus, int assignedMaterialCount,
                                 int inspectedMaterialCount, int findingCount,
                                 List<String> unresolvedQuestions, WorkerFailureInfo failure,
                                 List<Gap> gaps)
        implements RejectsUnknownFields {
    public enum CoverageStatus { COMPLETE, INCOMPLETE }
    public enum Gap {
        NO_FINDINGS,
        UNINSPECTED_MATERIALS,
        UNRESOLVED_QUESTIONS,
        PARTIAL_OUTCOME,
        FAILED_OUTCOME,
        CANCELLED_OUTCOME
    }

    public WorkerTaskCoverage {
        workerTaskId = WorkerContractSupport.identifier(workerTaskId, "coverage worker task id");
        if (executionStatus == null || coverageStatus == null || assignedMaterialCount < 0
                || inspectedMaterialCount < 0 || findingCount < 0
                || inspectedMaterialCount > assignedMaterialCount || unresolvedQuestions == null
                || gaps == null || gaps.size() > Gap.values().length
                || gaps.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("worker task coverage is incomplete or inconsistent");
        }
        if (assignedMaterialCount > WorkerTaskPacket.MAX_MATERIAL_PATHS
                || findingCount > WorkerResult.MAX_FINDINGS) {
            throw new IllegalArgumentException("worker task coverage exceeds contract hard limits");
        }
        unresolvedQuestions = WorkerContractSupport.sortedDistinctTexts(unresolvedQuestions,
                WorkerResult.MAX_UNRESOLVED_QUESTIONS, WorkerResult.MAX_QUESTION_UTF8_BYTES,
                "coverage unresolved question", false);
        gaps = List.copyOf(gaps.stream().distinct().sorted().toList());
        if (executionStatus == WorkerResult.Status.SUCCEEDED && failure != null) {
            throw new IllegalArgumentException("successful coverage cannot carry failure information");
        }
        if ((executionStatus == WorkerResult.Status.FAILED
                || executionStatus == WorkerResult.Status.CANCELLED) && failure == null) {
            throw new IllegalArgumentException("failed or cancelled coverage requires failure information");
        }
        if (executionStatus == WorkerResult.Status.SUCCEEDED && !unresolvedQuestions.isEmpty()) {
            throw new IllegalArgumentException("successful coverage cannot carry unresolved questions");
        }
        java.util.EnumSet<Gap> expected = java.util.EnumSet.noneOf(Gap.class);
        if (findingCount == 0) expected.add(Gap.NO_FINDINGS);
        if (inspectedMaterialCount < assignedMaterialCount) expected.add(Gap.UNINSPECTED_MATERIALS);
        if (!unresolvedQuestions.isEmpty()) expected.add(Gap.UNRESOLVED_QUESTIONS);
        switch (executionStatus) {
            case PARTIAL -> expected.add(Gap.PARTIAL_OUTCOME);
            case FAILED -> expected.add(Gap.FAILED_OUTCOME);
            case CANCELLED -> expected.add(Gap.CANCELLED_OUTCOME);
            case SUCCEEDED -> { }
        }
        CoverageStatus expectedStatus = expected.isEmpty() ? CoverageStatus.COMPLETE : CoverageStatus.INCOMPLETE;
        java.util.EnumSet<Gap> actual = gaps.isEmpty() ? java.util.EnumSet.noneOf(Gap.class)
                : java.util.EnumSet.copyOf(gaps);
        if (coverageStatus != expectedStatus || !expected.equals(actual)) {
            throw new IllegalArgumentException("worker task coverage gaps must match its structured outcome");
        }
    }

    static WorkerTaskCoverage from(WorkerResultAttestation attestation) {
        WorkerResult result = attestation.result();
        WorkerTaskPacket packet = attestation.taskPacket();
        WorkerExecutionReceipt receipt = attestation.executionReceipt();
        java.util.EnumSet<Gap> gaps = java.util.EnumSet.noneOf(Gap.class);
        if (result.findings().isEmpty()) gaps.add(Gap.NO_FINDINGS);
        if (receipt.inspectedPaths().size() < packet.materialAssignments().size()) {
            gaps.add(Gap.UNINSPECTED_MATERIALS);
        }
        if (!result.unresolvedQuestions().isEmpty()) gaps.add(Gap.UNRESOLVED_QUESTIONS);
        switch (result.status()) {
            case PARTIAL -> gaps.add(Gap.PARTIAL_OUTCOME);
            case FAILED -> gaps.add(Gap.FAILED_OUTCOME);
            case CANCELLED -> gaps.add(Gap.CANCELLED_OUTCOME);
            case SUCCEEDED -> { }
        }
        CoverageStatus status = gaps.isEmpty() ? CoverageStatus.COMPLETE : CoverageStatus.INCOMPLETE;
        return new WorkerTaskCoverage(result.workerTaskId(), result.status(), status,
                packet.materialAssignments().size(), receipt.inspectedPaths().size(),
                result.findings().size(), result.unresolvedQuestions(), result.failure(), List.copyOf(gaps));
    }
}
