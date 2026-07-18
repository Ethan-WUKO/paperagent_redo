package com.yanban.core.agent.worker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yanban.core.research.ProjectVersionRef;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic audit projection; it cannot complete a parent or mutate a Candidate. */
public final class CrossMaterialDifferenceReport implements RejectsUnknownFields {
    public enum AssessmentBasis { NO_TRUSTED_RULES }

    public static final int MAX_SOURCE_TASKS = 64;
    public static final int MAX_DIFFERENCES = 512;

    private final String parentRunId;
    private final ProjectVersionRef projectVersion;
    private final AssessmentBasis assessmentBasis;
    private final List<String> sourceWorkerTaskIds;
    private final List<CrossMaterialDifference> differences;
    private final List<WorkerTaskCoverage> coverage;
    private final WorkerBudgetUsage aggregateUsage;
    private final CrossMaterialReportFingerprint fingerprint;

    public CrossMaterialDifferenceReport(String parentRunId, ProjectVersionRef projectVersion,
                                         List<String> sourceWorkerTaskIds,
                                         List<CrossMaterialDifference> differences,
                                         List<WorkerTaskCoverage> coverage,
                                         WorkerBudgetUsage aggregateUsage) {
        this(parentRunId, projectVersion, AssessmentBasis.NO_TRUSTED_RULES, sourceWorkerTaskIds,
                differences, coverage, aggregateUsage, null, false);
    }

    @JsonCreator
    public static CrossMaterialDifferenceReport fromJson(
            @JsonProperty(value = "parentRunId", required = true) String parentRunId,
            @JsonProperty(value = "projectVersion", required = true) ProjectVersionRef projectVersion,
            @JsonProperty(value = "assessmentBasis", required = true) AssessmentBasis assessmentBasis,
            @JsonProperty(value = "sourceWorkerTaskIds", required = true) List<String> sourceWorkerTaskIds,
            @JsonProperty(value = "differences", required = true) List<CrossMaterialDifference> differences,
            @JsonProperty(value = "coverage", required = true) List<WorkerTaskCoverage> coverage,
            @JsonProperty(value = "aggregateUsage", required = true) WorkerBudgetUsage aggregateUsage,
            @JsonProperty(value = "fingerprint", required = true)
                    CrossMaterialReportFingerprint fingerprint) {
        return new CrossMaterialDifferenceReport(parentRunId, projectVersion, assessmentBasis,
                sourceWorkerTaskIds, differences, coverage, aggregateUsage, fingerprint, true);
    }

    private CrossMaterialDifferenceReport(String parentRunId, ProjectVersionRef projectVersion,
                                          AssessmentBasis assessmentBasis, List<String> sourceWorkerTaskIds,
                                          List<CrossMaterialDifference> differences,
                                          List<WorkerTaskCoverage> coverage,
                                          WorkerBudgetUsage aggregateUsage,
                                          CrossMaterialReportFingerprint suppliedFingerprint,
                                          boolean verifyFingerprint) {
        this.parentRunId = WorkerContractSupport.identifier(parentRunId, "difference report parent run id");
        if (projectVersion == null || assessmentBasis == null || aggregateUsage == null) {
            throw new IllegalArgumentException("difference report binding, basis, and usage are required");
        }
        this.projectVersion = projectVersion;
        this.assessmentBasis = assessmentBasis;
        this.sourceWorkerTaskIds = normalizeTaskIds(sourceWorkerTaskIds);
        this.differences = normalizeDifferences(differences);
        this.coverage = normalizeCoverage(coverage);
        this.aggregateUsage = aggregateUsage;
        validateProjection();
        CrossMaterialReportFingerprint calculated = calculateFingerprint();
        if (verifyFingerprint && !calculated.equals(suppliedFingerprint)) {
            throw new IllegalArgumentException("difference report fingerprint does not match report content");
        }
        this.fingerprint = calculated;
    }

    private static List<String> normalizeTaskIds(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_SOURCE_TASKS
                || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("difference report source tasks exceed their limit or contain null");
        }
        return List.copyOf(values.stream()
                .map(value -> WorkerContractSupport.identifier(value, "difference report source task id"))
                .distinct().sorted().toList());
    }

    private static List<CrossMaterialDifference> normalizeDifferences(List<CrossMaterialDifference> values) {
        if (values == null || values.size() > MAX_DIFFERENCES
                || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("difference report entries exceed their limit or contain null");
        }
        return List.copyOf(values.stream().distinct()
                .sorted(CrossMaterialDifference.deterministicOrder()).toList());
    }

    private static List<WorkerTaskCoverage> normalizeCoverage(List<WorkerTaskCoverage> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_SOURCE_TASKS
                || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("difference report coverage is required and bounded");
        }
        return List.copyOf(values.stream().distinct()
                .sorted(java.util.Comparator.comparing(WorkerTaskCoverage::workerTaskId)).toList());
    }

    private void validateProjection() {
        Set<String> differenceIds = new HashSet<>();
        Set<String> reportTasks = new HashSet<>(sourceWorkerTaskIds);
        Set<String> coveredTasks = new HashSet<>();
        Set<com.yanban.core.research.ResearchEvidenceRef> projectedEvidence = new HashSet<>();
        long projectedSummaryBytes = 0;
        int coveredFindings = 0;
        int coveredInspectedPaths = 0;
        for (WorkerTaskCoverage item : coverage) {
            if (!coveredTasks.add(item.workerTaskId())) {
                throw new IllegalArgumentException("difference report contains duplicate task coverage");
            }
            coveredFindings = Math.addExact(coveredFindings, item.findingCount());
            coveredInspectedPaths = Math.addExact(coveredInspectedPaths, item.inspectedMaterialCount());
            for (String question : item.unresolvedQuestions()) {
                projectedSummaryBytes += WorkerContractSupport.utf8Bytes(question);
            }
            if (item.failure() != null) {
                projectedSummaryBytes += WorkerContractSupport.utf8Bytes(item.failure().message());
            }
        }
        if (!coveredTasks.equals(reportTasks)) {
            throw new IllegalArgumentException("difference report coverage must match every source task exactly");
        }
        for (CrossMaterialDifference difference : differences) {
            if (!differenceIds.add(difference.differenceId())) {
                throw new IllegalArgumentException("difference report contains an ambiguous difference id");
            }
            if (!reportTasks.containsAll(difference.sourceWorkerTaskIds())) {
                throw new IllegalArgumentException("difference refers to a task outside the report boundary");
            }
            if (difference.evidenceRefs().stream()
                    .anyMatch(evidence -> !projectVersion.equals(evidence.projectVersion()))) {
                throw new IllegalArgumentException("difference evidence uses a different Project version");
            }
            projectedEvidence.addAll(difference.evidenceRefs());
            for (String summary : difference.observationSummaries()) {
                projectedSummaryBytes += WorkerContractSupport.utf8Bytes(summary);
            }
        }
        if (aggregateUsage.inputPaths() > WorkerBudget.HARD_MAX_INPUT_PATHS
                || aggregateUsage.toolCalls() > WorkerBudget.HARD_MAX_TOOL_CALLS
                || aggregateUsage.findings() > WorkerBudget.HARD_MAX_FINDINGS
                || aggregateUsage.evidenceRefs() > WorkerBudget.HARD_MAX_EVIDENCE_REFS
                || aggregateUsage.bytesInspected() > WorkerBudget.HARD_MAX_BYTES_INSPECTED
                || aggregateUsage.summaryUtf8Bytes() > WorkerBudget.HARD_MAX_SUMMARY_UTF8_BYTES) {
            throw new IllegalArgumentException("difference report usage exceeds contract hard limits");
        }
        if (coveredFindings != aggregateUsage.findings()
                || coveredInspectedPaths != aggregateUsage.inputPaths()
                || differences.size() > aggregateUsage.findings()
                || projectedEvidence.size() > aggregateUsage.evidenceRefs()
                || projectedSummaryBytes > aggregateUsage.summaryUtf8Bytes()) {
            throw new IllegalArgumentException("difference report projection exceeds attested result usage");
        }
    }

    private CrossMaterialReportFingerprint calculateFingerprint() {
        return new CrossMaterialReportFingerprint(WorkerContractSupport.digest(
                "yanban-cross-material-difference-report-v1", writer -> {
                    writer.field(parentRunId);
                    writer.field(projectVersion.value());
                    writer.field(assessmentBasis.name());
                    sourceWorkerTaskIds.forEach(writer::field);
                    for (CrossMaterialDifference difference : differences) {
                        writer.field(difference.differenceId());
                        writer.field(difference.status().name());
                        difference.materialTypes().forEach(type -> writer.field(type.name()));
                        difference.observationSummaries().forEach(writer::field);
                        difference.sourceWorkerTaskIds().forEach(writer::field);
                        difference.evidenceRefs().forEach(writer::evidence);
                    }
                    for (WorkerTaskCoverage item : coverage) {
                        writer.field(item.workerTaskId());
                        writer.field(item.executionStatus().name());
                        writer.field(item.coverageStatus().name());
                        writer.number(item.assignedMaterialCount());
                        writer.number(item.inspectedMaterialCount());
                        writer.number(item.findingCount());
                        item.unresolvedQuestions().forEach(writer::field);
                        writer.field(item.failure() == null ? "" : item.failure().code());
                        writer.field(item.failure() == null ? "" : item.failure().message());
                        writer.field(item.failure() == null ? "" : Boolean.toString(item.failure().retryable()));
                        item.gaps().forEach(gap -> writer.field(gap.name()));
                    }
                    writer.usage(aggregateUsage);
                }));
    }

    @JsonProperty("parentRunId") public String parentRunId() { return parentRunId; }
    @JsonProperty("projectVersion") public ProjectVersionRef projectVersion() { return projectVersion; }
    @JsonProperty("assessmentBasis") public AssessmentBasis assessmentBasis() { return assessmentBasis; }
    @JsonProperty("sourceWorkerTaskIds") public List<String> sourceWorkerTaskIds() { return sourceWorkerTaskIds; }
    @JsonProperty("differences") public List<CrossMaterialDifference> differences() { return differences; }
    @JsonProperty("coverage") public List<WorkerTaskCoverage> coverage() { return coverage; }
    @JsonProperty("aggregateUsage") public WorkerBudgetUsage aggregateUsage() { return aggregateUsage; }
    @JsonProperty("fingerprint")
    public CrossMaterialReportFingerprint fingerprint() { return fingerprint; }

    @JsonIgnore public boolean completionAuthority() { return false; }
    @JsonIgnore public boolean writeAuthority() { return false; }
    @JsonIgnore public boolean applyAuthority() { return false; }
    @JsonIgnore public boolean parentTaskCompleted() { return false; }
    @JsonIgnore public boolean changesProject() { return false; }
    @JsonIgnore public String candidateApplicationStatus() { return "NOT_APPLIED"; }

    @Override
    public boolean equals(Object other) {
        return other instanceof CrossMaterialDifferenceReport report && fingerprint.equals(report.fingerprint);
    }

    @Override
    public int hashCode() {
        return fingerprint.hashCode();
    }
}
