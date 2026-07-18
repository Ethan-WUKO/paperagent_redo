package com.yanban.core.agent.worker;

import com.yanban.core.research.ResearchEvidenceRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Pure deterministic aggregation over server-attested Worker results. */
public final class CrossMaterialDifferenceAggregator {
    private CrossMaterialDifferenceAggregator() { }

    public static CrossMaterialDifferenceReport aggregateWithoutTrustedRules(
            WorkerServerAuthority authority, List<?> resultAttestations) {
        if (authority == null || resultAttestations == null) {
            throw new IllegalArgumentException("aggregation authority and result attestations are required");
        }
        if (resultAttestations.isEmpty()) {
            throw new IllegalArgumentException("aggregation requires at least one attested Worker result");
        }
        if (resultAttestations.size() > CrossMaterialDifferenceReport.MAX_SOURCE_TASKS) {
            throw new IllegalArgumentException("too many Worker results for one aggregation");
        }

        List<WorkerResultAttestation> accepted = new ArrayList<>();
        Set<String> taskIds = new HashSet<>();
        WorkerBudgetUsage aggregateUsage = new WorkerBudgetUsage(0, 0, 0, 0, 0, 0);
        for (Object candidate : resultAttestations) {
            if (!(candidate instanceof WorkerResultAttestation attestation)) {
                throw new IllegalArgumentException("aggregation accepts only server-attested Worker results");
            }
            if (!authority.sameBoundary(attestation.authority())) {
                throw new IllegalArgumentException("Worker result crosses user, Project, version, or parent boundary");
            }
            WorkerResult result = attestation.result();
            if (!taskIds.add(result.workerTaskId())) {
                throw new IllegalArgumentException("aggregation contains duplicate Worker task results");
            }
            accepted.add(attestation);
            aggregateUsage = addUsage(aggregateUsage, result.budgetUsage());
            authority.parentBudget().validate(aggregateUsage);
        }
        accepted.sort(java.util.Comparator.comparing(item -> item.result().workerTaskId()));

        Map<String, DifferenceAccumulator> grouped = new TreeMap<>();
        for (WorkerResultAttestation attestation : accepted) {
            WorkerResult result = attestation.result();
            for (WorkerFinding finding : result.findings()) {
                grouped.computeIfAbsent(finding.findingId(), DifferenceAccumulator::new)
                        .add(result.workerTaskId(), finding);
            }
        }
        if (grouped.size() > CrossMaterialDifferenceReport.MAX_DIFFERENCES) {
            throw new IllegalArgumentException("aggregated differences exceed the report limit");
        }

        List<CrossMaterialDifference> differences = grouped.values().stream()
                .map(DifferenceAccumulator::unresolved).toList();
        List<WorkerTaskCoverage> coverage = accepted.stream().map(WorkerTaskCoverage::from).toList();
        return new CrossMaterialDifferenceReport(authority.parentRunId(), authority.projectVersion(),
                accepted.stream().map(item -> item.result().workerTaskId()).toList(),
                differences, coverage, aggregateUsage);
    }

    private static WorkerBudgetUsage addUsage(WorkerBudgetUsage left, WorkerBudgetUsage right) {
        try {
            return new WorkerBudgetUsage(
                    Math.addExact(left.inputPaths(), right.inputPaths()),
                    Math.addExact(left.toolCalls(), right.toolCalls()),
                    Math.addExact(left.findings(), right.findings()),
                    Math.addExact(left.evidenceRefs(), right.evidenceRefs()),
                    Math.addExact(left.bytesInspected(), right.bytesInspected()),
                    Math.addExact(left.summaryUtf8Bytes(), right.summaryUtf8Bytes()));
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("aggregate Worker usage overflowed", ex);
        }
    }

    private static final class DifferenceAccumulator {
        private final String differenceId;
        private final Set<WorkerMaterialType> materialTypes = new TreeSet<>();
        private final Set<String> summaries = new TreeSet<>();
        private final Set<String> taskIds = new TreeSet<>();
        private final Set<ResearchEvidenceRef> evidence = new TreeSet<>(WorkerContractSupport.EVIDENCE_ORDER);

        private DifferenceAccumulator(String differenceId) {
            this.differenceId = differenceId;
        }

        private void add(String taskId, WorkerFinding finding) {
            materialTypes.add(finding.materialType());
            summaries.add(finding.summary());
            taskIds.add(taskId);
            evidence.addAll(finding.evidenceRefs());
        }

        private CrossMaterialDifference unresolved() {
            return new CrossMaterialDifference(differenceId, CrossMaterialDifference.Status.UNRESOLVED,
                    List.copyOf(materialTypes), List.copyOf(summaries), List.copyOf(taskIds),
                    List.copyOf(evidence));
        }
    }
}
