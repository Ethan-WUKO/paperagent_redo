package com.yanban.core.agent.worker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchEvidenceRef;
import java.util.List;

/** Immutable, deterministic, authority-free input for one read-only Worker. */
public final class WorkerTaskPacket implements RejectsUnknownFields {
    public static final int MAX_MATERIAL_PATHS = 256;
    public static final int MAX_ALLOWED_TOOLS = 32;
    public static final int MAX_ALLOWED_FINDING_KEYS = 128;
    public static final int MAX_SUCCESS_CRITERIA = 64;
    public static final int MAX_OBJECTIVE_UTF8_BYTES = 16 * 1024;
    public static final int MAX_CRITERION_UTF8_BYTES = 2 * 1024;
    public static final int MAX_EVIDENCE_REFS = 1_024;

    private final String workerTaskId;
    private final String parentRunId;
    private final ProjectVersionRef projectVersion;
    private final List<WorkerMaterialAssignment> materialAssignments;
    private final String objective;
    private final List<String> successCriteria;
    private final List<String> allowedReadTools;
    private final List<String> allowedFindingKeys;
    private final WorkerBudget budget;
    private final List<ResearchEvidenceRef> evidenceRefs;
    private final WorkerTaskFingerprint fingerprint;

    public WorkerTaskPacket(String workerTaskId, String parentRunId, ProjectVersionRef projectVersion,
                            List<WorkerMaterialAssignment> materialAssignments, String objective,
                            List<String> successCriteria, List<String> allowedReadTools,
                            List<String> allowedFindingKeys, WorkerBudget budget,
                            List<ResearchEvidenceRef> evidenceRefs) {
        this(workerTaskId, parentRunId, projectVersion, materialAssignments, objective, successCriteria,
                allowedReadTools, allowedFindingKeys, budget, evidenceRefs, null, false);
    }

    @JsonCreator
    public static WorkerTaskPacket fromJson(
            @JsonProperty(value = "workerTaskId", required = true) String workerTaskId,
            @JsonProperty(value = "parentRunId", required = true) String parentRunId,
            @JsonProperty(value = "projectVersion", required = true) ProjectVersionRef projectVersion,
            @JsonProperty(value = "materialAssignments", required = true)
                    List<WorkerMaterialAssignment> materialAssignments,
            @JsonProperty(value = "objective", required = true) String objective,
            @JsonProperty(value = "successCriteria", required = true) List<String> successCriteria,
            @JsonProperty(value = "allowedReadTools", required = true) List<String> allowedReadTools,
            @JsonProperty(value = "allowedFindingKeys", required = true) List<String> allowedFindingKeys,
            @JsonProperty(value = "budget", required = true) WorkerBudget budget,
            @JsonProperty(value = "evidenceRefs", required = true)
                    @JsonDeserialize(contentUsing = StrictResearchEvidenceRefDeserializer.class)
                    List<ResearchEvidenceRef> evidenceRefs,
            @JsonProperty(value = "fingerprint", required = true) WorkerTaskFingerprint fingerprint) {
        return new WorkerTaskPacket(workerTaskId, parentRunId, projectVersion, materialAssignments, objective,
                successCriteria, allowedReadTools, allowedFindingKeys, budget, evidenceRefs, fingerprint, true);
    }

    private WorkerTaskPacket(String workerTaskId, String parentRunId, ProjectVersionRef projectVersion,
                             List<WorkerMaterialAssignment> materialAssignments, String objective,
                             List<String> successCriteria, List<String> allowedReadTools,
                             List<String> allowedFindingKeys, WorkerBudget budget,
                             List<ResearchEvidenceRef> evidenceRefs,
                             WorkerTaskFingerprint suppliedFingerprint, boolean verifyFingerprint) {
        this.workerTaskId = WorkerContractSupport.identifier(workerTaskId, "workerTaskId");
        this.parentRunId = WorkerContractSupport.identifier(parentRunId, "parentRunId");
        if (projectVersion == null || budget == null) {
            throw new IllegalArgumentException("worker task version and budget are required");
        }
        this.projectVersion = projectVersion;
        this.materialAssignments = WorkerContractSupport.sortedDistinctAssignments(materialAssignments,
                MAX_MATERIAL_PATHS);
        this.objective = WorkerContractSupport.safeText(objective, "worker objective",
                MAX_OBJECTIVE_UTF8_BYTES, false);
        this.successCriteria = WorkerContractSupport.sortedDistinctTexts(successCriteria, MAX_SUCCESS_CRITERIA,
                MAX_CRITERION_UTF8_BYTES, "worker success criterion", true);
        this.allowedReadTools = WorkerContractSupport.sortedDistinctTools(allowedReadTools, MAX_ALLOWED_TOOLS);
        this.allowedFindingKeys = WorkerContractSupport.sortedDistinctFindingKeys(allowedFindingKeys,
                MAX_ALLOWED_FINDING_KEYS);
        this.budget = budget;
        this.evidenceRefs = WorkerContractSupport.sortedDistinctEvidence(evidenceRefs, MAX_EVIDENCE_REFS,
                "worker task evidence");
        if (this.materialAssignments.size() > budget.maxInputPaths()
                || this.evidenceRefs.size() > budget.maxEvidenceRefs()) {
            throw new IllegalArgumentException("worker task assignment exceeds its declared budget");
        }
        WorkerContractSupport.requireEvidenceScope(projectVersion, materialScope(), this.evidenceRefs,
                "worker task evidence");
        WorkerTaskFingerprint calculated = calculateFingerprint();
        if (verifyFingerprint && !calculated.equals(suppliedFingerprint)) {
            throw new IllegalArgumentException("worker task fingerprint does not match packet content");
        }
        this.fingerprint = calculated;
    }

    private WorkerTaskFingerprint calculateFingerprint() {
        return new WorkerTaskFingerprint(WorkerContractSupport.digest("yanban-worker-task-packet-v1", writer -> {
            writer.field(workerTaskId);
            writer.field(parentRunId);
            writer.field(projectVersion.value());
            for (WorkerMaterialAssignment assignment : materialAssignments) {
                writer.field(assignment.relativePath().value());
                writer.field(assignment.materialType().name());
            }
            writer.field(objective);
            successCriteria.forEach(writer::field);
            allowedReadTools.forEach(writer::field);
            allowedFindingKeys.forEach(writer::field);
            writer.budget(budget);
            evidenceRefs.forEach(writer::evidence);
        }));
    }

    @JsonProperty("workerTaskId") public String workerTaskId() { return workerTaskId; }
    @JsonProperty("parentRunId") public String parentRunId() { return parentRunId; }
    @JsonProperty("projectVersion") public ProjectVersionRef projectVersion() { return projectVersion; }
    @JsonProperty("materialAssignments")
    public List<WorkerMaterialAssignment> materialAssignments() { return materialAssignments; }
    @JsonIgnore
    public List<ProjectRelativePath> materialScope() {
        return materialAssignments.stream().map(WorkerMaterialAssignment::relativePath).toList();
    }
    @JsonProperty("objective") public String objective() { return objective; }
    @JsonProperty("successCriteria") public List<String> successCriteria() { return successCriteria; }
    @JsonProperty("allowedReadTools") public List<String> allowedReadTools() { return allowedReadTools; }
    @JsonProperty("allowedFindingKeys") public List<String> allowedFindingKeys() { return allowedFindingKeys; }
    @JsonProperty("budget") public WorkerBudget budget() { return budget; }
    @JsonProperty("evidenceRefs") public List<ResearchEvidenceRef> evidenceRefs() { return evidenceRefs; }
    @JsonProperty("fingerprint") public WorkerTaskFingerprint fingerprint() { return fingerprint; }

    @JsonIgnore
    public WorkerMaterialType materialTypeOf(ProjectRelativePath path) {
        return materialAssignments.stream().filter(assignment -> assignment.relativePath().equals(path))
                .map(WorkerMaterialAssignment::materialType).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("path is outside the worker material assignment"));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkerTaskPacket packet && fingerprint.equals(packet.fingerprint);
    }

    @Override
    public int hashCode() {
        return fingerprint.hashCode();
    }
}
