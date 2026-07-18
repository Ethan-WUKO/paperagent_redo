package com.yanban.core.agent.worker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchEvidenceRef;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structured execution outcome. It grants no parent completion, write, or apply authority. */
public final class WorkerResult implements RejectsUnknownFields {
    public enum Status { SUCCEEDED, PARTIAL, FAILED, CANCELLED }

    public static final int MAX_FINDINGS = 512;
    public static final int MAX_TOOL_OBSERVATIONS = 256;
    public static final int MAX_UNRESOLVED_QUESTIONS = 128;
    public static final int MAX_QUESTION_UTF8_BYTES = 4 * 1024;
    public static final int MAX_EVIDENCE_REFS = 1_024;

    private static final Comparator<WorkerFinding> FINDING_ORDER = Comparator
            .comparing(WorkerFinding::findingId)
            .thenComparing(WorkerFinding::materialType)
            .thenComparing(WorkerFinding::summary)
            .thenComparing(finding -> WorkerContractSupport.evidenceKey(finding.evidenceRefs()));
    private static final Comparator<WorkerToolObservation> TOOL_OBSERVATION_ORDER = Comparator
            .comparing(WorkerToolObservation::toolName)
            .thenComparing(WorkerToolObservation::summary)
            .thenComparing(observation -> WorkerContractSupport.evidenceKey(observation.evidenceRefs()));

    private final String workerTaskId;
    private final String parentRunId;
    private final ProjectVersionRef projectVersion;
    private final WorkerTaskFingerprint taskPacketFingerprint;
    private final Status status;
    private final List<WorkerFinding> findings;
    private final List<WorkerToolObservation> toolObservations;
    private final List<String> unresolvedQuestions;
    private final WorkerFailureInfo failure;
    private final WorkerBudgetUsage budgetUsage;
    private final List<ResearchEvidenceRef> evidenceRefs;
    private final WorkerResultFingerprint fingerprint;

    public WorkerResult(WorkerTaskPacket packet, Status status, List<WorkerFinding> findings,
                        List<WorkerToolObservation> toolObservations, List<String> unresolvedQuestions,
                        WorkerFailureInfo failure, WorkerBudgetUsage budgetUsage,
                        List<ResearchEvidenceRef> evidenceRefs) {
        this(requirePacket(packet).workerTaskId(), packet.parentRunId(), packet.projectVersion(),
                packet.fingerprint(), status, findings, toolObservations, unresolvedQuestions,
                failure, budgetUsage, evidenceRefs, null, false);
        validateAgainst(packet);
    }

    @JsonCreator
    public static WorkerResult fromJson(
            @JsonProperty(value = "workerTaskId", required = true) String workerTaskId,
            @JsonProperty(value = "parentRunId", required = true) String parentRunId,
            @JsonProperty(value = "projectVersion", required = true) ProjectVersionRef projectVersion,
            @JsonProperty(value = "taskPacketFingerprint", required = true)
                    WorkerTaskFingerprint taskPacketFingerprint,
            @JsonProperty(value = "status", required = true) Status status,
            @JsonProperty(value = "findings", required = true) List<WorkerFinding> findings,
            @JsonProperty(value = "toolObservations", required = true)
                    List<WorkerToolObservation> toolObservations,
            @JsonProperty(value = "unresolvedQuestions", required = true) List<String> unresolvedQuestions,
            @JsonProperty(value = "failure", required = true) WorkerFailureInfo failure,
            @JsonProperty(value = "budgetUsage", required = true) WorkerBudgetUsage budgetUsage,
            @JsonProperty(value = "evidenceRefs", required = true)
                    @JsonDeserialize(contentUsing = StrictResearchEvidenceRefDeserializer.class)
                    List<ResearchEvidenceRef> evidenceRefs,
            @JsonProperty(value = "fingerprint", required = true) WorkerResultFingerprint fingerprint) {
        return new WorkerResult(workerTaskId, parentRunId, projectVersion, taskPacketFingerprint, status,
                findings, toolObservations, unresolvedQuestions, failure, budgetUsage, evidenceRefs,
                fingerprint, true);
    }

    private WorkerResult(String workerTaskId, String parentRunId, ProjectVersionRef projectVersion,
                         WorkerTaskFingerprint taskPacketFingerprint, Status status,
                         List<WorkerFinding> findings, List<WorkerToolObservation> toolObservations,
                         List<String> unresolvedQuestions, WorkerFailureInfo failure,
                         WorkerBudgetUsage budgetUsage, List<ResearchEvidenceRef> evidenceRefs,
                         WorkerResultFingerprint suppliedFingerprint, boolean verifyFingerprint) {
        this.workerTaskId = WorkerContractSupport.identifier(workerTaskId, "worker result task id");
        this.parentRunId = WorkerContractSupport.identifier(parentRunId, "worker result parent run id");
        if (projectVersion == null || taskPacketFingerprint == null || status == null || budgetUsage == null) {
            throw new IllegalArgumentException("worker result binding, status, and usage are required");
        }
        this.projectVersion = projectVersion;
        this.taskPacketFingerprint = taskPacketFingerprint;
        this.status = status;
        this.findings = normalizeFindings(findings);
        this.toolObservations = normalizeToolObservations(toolObservations);
        this.unresolvedQuestions = WorkerContractSupport.sortedDistinctTexts(unresolvedQuestions,
                MAX_UNRESOLVED_QUESTIONS, MAX_QUESTION_UTF8_BYTES, "worker unresolved question", false);
        this.failure = failure;
        this.budgetUsage = budgetUsage;
        this.evidenceRefs = WorkerContractSupport.sortedDistinctEvidence(evidenceRefs, MAX_EVIDENCE_REFS,
                "worker result evidence");
        validateOutcomeShape();
        WorkerResultFingerprint calculated = calculateFingerprint();
        if (verifyFingerprint && !calculated.equals(suppliedFingerprint)) {
            throw new IllegalArgumentException("worker result fingerprint does not match result content");
        }
        this.fingerprint = calculated;
    }

    private static WorkerTaskPacket requirePacket(WorkerTaskPacket packet) {
        if (packet == null) throw new IllegalArgumentException("worker task packet is required");
        return packet;
    }

    private static List<WorkerFinding> normalizeFindings(List<WorkerFinding> values) {
        if (values == null || values.size() > MAX_FINDINGS
                || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("worker findings exceed their limit or contain null");
        }
        return List.copyOf(values.stream().distinct().sorted(FINDING_ORDER).toList());
    }

    private static List<WorkerToolObservation> normalizeToolObservations(List<WorkerToolObservation> values) {
        if (values == null || values.size() > MAX_TOOL_OBSERVATIONS
                || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("worker tool observations exceed their limit or contain null");
        }
        return List.copyOf(values.stream().distinct().sorted(TOOL_OBSERVATION_ORDER).toList());
    }

    private void validateOutcomeShape() {
        if (status == Status.SUCCEEDED && failure != null) {
            throw new IllegalArgumentException("a successful worker result cannot carry failure information");
        }
        if (status == Status.SUCCEEDED && !unresolvedQuestions.isEmpty()) {
            throw new IllegalArgumentException("a worker result with unresolved questions must be PARTIAL");
        }
        if ((status == Status.FAILED || status == Status.CANCELLED) && failure == null) {
            throw new IllegalArgumentException("failed or cancelled worker results require failure information");
        }
        Set<ResearchEvidenceRef> envelope = new HashSet<>(evidenceRefs);
        for (WorkerFinding finding : findings) {
            requireVersion(finding.evidenceRefs());
            if (!envelope.containsAll(finding.evidenceRefs())) {
                throw new IllegalArgumentException("worker result evidence must cover every finding");
            }
        }
        for (WorkerToolObservation observation : toolObservations) {
            requireVersion(observation.evidenceRefs());
            if (!envelope.containsAll(observation.evidenceRefs())) {
                throw new IllegalArgumentException("worker result evidence must cover every tool observation");
            }
        }
        requireVersion(evidenceRefs);
        long measuredText = measuredSummaryUtf8Bytes();
        if (budgetUsage.findings() != findings.size()
                || budgetUsage.evidenceRefs() != evidenceRefs.size()
                || budgetUsage.toolCalls() < toolObservations.size()
                || budgetUsage.summaryUtf8Bytes() != measuredText) {
            throw new IllegalArgumentException("worker result usage does not match its structured content");
        }
        if (budgetUsage.inputPaths() > WorkerBudget.HARD_MAX_INPUT_PATHS
                || budgetUsage.toolCalls() > WorkerBudget.HARD_MAX_TOOL_CALLS
                || budgetUsage.findings() > WorkerBudget.HARD_MAX_FINDINGS
                || budgetUsage.evidenceRefs() > WorkerBudget.HARD_MAX_EVIDENCE_REFS
                || budgetUsage.bytesInspected() > WorkerBudget.HARD_MAX_BYTES_INSPECTED
                || budgetUsage.summaryUtf8Bytes() > WorkerBudget.HARD_MAX_SUMMARY_UTF8_BYTES) {
            throw new IllegalArgumentException("worker result usage exceeds contract hard limits");
        }
    }

    private void requireVersion(List<ResearchEvidenceRef> evidence) {
        if (evidence.stream().anyMatch(item -> !projectVersion.equals(item.projectVersion()))) {
            throw new IllegalArgumentException("worker result evidence uses a different Project version");
        }
    }

    void validateAgainst(WorkerTaskPacket packet) {
        if (!workerTaskId.equals(packet.workerTaskId()) || !parentRunId.equals(packet.parentRunId())
                || !projectVersion.equals(packet.projectVersion())
                || !taskPacketFingerprint.equals(packet.fingerprint())) {
            throw new IllegalArgumentException("worker result does not belong to the attested task packet");
        }
        WorkerContractSupport.requireEvidenceScope(projectVersion, packet.materialScope(), evidenceRefs,
                "worker result evidence");
        for (WorkerFinding finding : findings) {
            if (!packet.allowedFindingKeys().contains(finding.findingId())) {
                throw new IllegalArgumentException("worker finding key is outside the task allowlist");
            }
            for (ResearchEvidenceRef evidence : finding.evidenceRefs()) {
                if (packet.materialTypeOf(evidence.relativePath()) != finding.materialType()) {
                    throw new IllegalArgumentException(
                            "worker finding material type does not match its server assignment");
                }
            }
        }
        for (WorkerToolObservation observation : toolObservations) {
            if (!packet.allowedReadTools().contains(observation.toolName())) {
                throw new IllegalArgumentException("worker result used a tool outside the task allowlist");
            }
        }
        if (budgetUsage.inputPaths() > packet.materialAssignments().size()) {
            throw new IllegalArgumentException("worker result inspected more paths than were assigned");
        }
        packet.budget().validate(budgetUsage);
    }

    void validateAgainstReceipt(WorkerTaskPacket packet, WorkerExecutionReceipt receipt) {
        Set<ResearchEvidenceRef> trustedEvidence = new HashSet<>(packet.evidenceRefs());
        trustedEvidence.addAll(receipt.observedEvidence());
        if (!trustedEvidence.containsAll(evidenceRefs)) {
            throw new IllegalArgumentException("worker result contains Evidence absent from trusted execution facts");
        }
        for (WorkerToolObservation observation : toolObservations) {
            if (!receipt.executedTools().contains(observation.toolName())) {
                throw new IllegalArgumentException("worker result claims a tool absent from execution facts");
            }
        }
        if (budgetUsage.inputPaths() != receipt.inspectedPaths().size()
                || budgetUsage.toolCalls() != receipt.toolCallCount()
                || budgetUsage.bytesInspected() != receipt.bytesInspected()) {
            throw new IllegalArgumentException("worker result usage does not match execution facts");
        }
    }

    private long measuredSummaryUtf8Bytes() {
        long total = 0;
        for (WorkerFinding finding : findings) total += WorkerContractSupport.utf8Bytes(finding.summary());
        for (WorkerToolObservation observation : toolObservations) {
            total += WorkerContractSupport.utf8Bytes(observation.summary());
        }
        for (String question : unresolvedQuestions) total += WorkerContractSupport.utf8Bytes(question);
        if (failure != null) total += WorkerContractSupport.utf8Bytes(failure.message());
        return total;
    }

    private WorkerResultFingerprint calculateFingerprint() {
        return new WorkerResultFingerprint(WorkerContractSupport.digest("yanban-worker-result-v1", writer -> {
            writer.field(workerTaskId);
            writer.field(parentRunId);
            writer.field(projectVersion.value());
            writer.field(taskPacketFingerprint.sha256());
            writer.field(status.name());
            for (WorkerFinding finding : findings) {
                writer.field(finding.findingId());
                writer.field(finding.materialType().name());
                writer.field(finding.summary());
                finding.evidenceRefs().forEach(writer::evidence);
            }
            for (WorkerToolObservation observation : toolObservations) {
                writer.field(observation.toolName());
                writer.field(observation.summary());
                observation.evidenceRefs().forEach(writer::evidence);
            }
            unresolvedQuestions.forEach(writer::field);
            writer.field(failure == null ? "" : failure.code());
            writer.field(failure == null ? "" : failure.message());
            writer.field(failure == null ? "" : Boolean.toString(failure.retryable()));
            writer.usage(budgetUsage);
            evidenceRefs.forEach(writer::evidence);
        }));
    }

    @JsonProperty("workerTaskId") public String workerTaskId() { return workerTaskId; }
    @JsonProperty("parentRunId") public String parentRunId() { return parentRunId; }
    @JsonProperty("projectVersion") public ProjectVersionRef projectVersion() { return projectVersion; }
    @JsonProperty("taskPacketFingerprint")
    public WorkerTaskFingerprint taskPacketFingerprint() { return taskPacketFingerprint; }
    @JsonProperty("status") public Status status() { return status; }
    @JsonProperty("findings") public List<WorkerFinding> findings() { return findings; }
    @JsonProperty("toolObservations")
    public List<WorkerToolObservation> toolObservations() { return toolObservations; }
    @JsonProperty("unresolvedQuestions") public List<String> unresolvedQuestions() { return unresolvedQuestions; }
    @JsonProperty("failure") public WorkerFailureInfo failure() { return failure; }
    @JsonProperty("budgetUsage") public WorkerBudgetUsage budgetUsage() { return budgetUsage; }
    @JsonProperty("evidenceRefs") public List<ResearchEvidenceRef> evidenceRefs() { return evidenceRefs; }
    @JsonProperty("fingerprint") public WorkerResultFingerprint fingerprint() { return fingerprint; }

    @JsonIgnore public boolean completionAuthority() { return false; }
    @JsonIgnore public boolean writeAuthority() { return false; }
    @JsonIgnore public boolean applyAuthority() { return false; }
    @JsonIgnore public boolean parentTaskCompleted() { return false; }
    @JsonIgnore public boolean carriesCandidate() { return false; }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkerResult result && fingerprint.equals(result.fingerprint);
    }

    @Override
    public int hashCode() {
        return fingerprint.hashCode();
    }
}
