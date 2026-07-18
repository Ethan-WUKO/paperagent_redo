package com.yanban.core.agent.worker;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ResearchEvidenceRef;
import java.util.List;

/** Server-owned facts from a future trusted read-only execution layer. */
@JsonSerialize(using = WorkerServerOnlySerializer.class)
@JsonDeserialize(using = WorkerServerOnlyDeserializer.class)
public final class WorkerExecutionReceipt {
    private final WorkerTaskAttestation taskAttestation;
    private final WorkerTaskFingerprint taskFingerprint;
    private final List<ProjectRelativePath> inspectedPaths;
    private final List<String> executedTools;
    private final List<ResearchEvidenceRef> observedEvidence;
    private final long bytesInspected;
    private final int toolCallCount;

    WorkerExecutionReceipt(WorkerTaskAttestation taskAttestation,
                           List<ProjectRelativePath> inspectedPaths,
                           List<String> executedTools,
                           List<ResearchEvidenceRef> observedEvidence,
                           long bytesInspected, int toolCallCount) {
        this.taskAttestation = taskAttestation;
        this.taskFingerprint = taskAttestation.packet().fingerprint();
        this.inspectedPaths = inspectedPaths;
        this.executedTools = executedTools;
        this.observedEvidence = observedEvidence;
        this.bytesInspected = bytesInspected;
        this.toolCallCount = toolCallCount;
    }

    public WorkerTaskFingerprint taskFingerprint() { return taskFingerprint; }
    public List<ProjectRelativePath> inspectedPaths() { return inspectedPaths; }
    public List<String> executedTools() { return executedTools; }
    public List<ResearchEvidenceRef> observedEvidence() { return observedEvidence; }
    public long bytesInspected() { return bytesInspected; }
    public int toolCallCount() { return toolCallCount; }
    public WorkerAccessMode accessMode() { return WorkerAccessMode.READ_ONLY; }
    public boolean canWriteCandidate() { return false; }
    public boolean canApplyRevision() { return false; }
    public boolean canExecuteCommands() { return false; }
    public boolean canUseNetwork() { return false; }
    public boolean canCompleteParentTask() { return false; }

    boolean belongsTo(WorkerTaskAttestation candidate) {
        return candidate != null && taskFingerprint.equals(candidate.packet().fingerprint())
                && taskAttestation.authority().sameBoundary(candidate.authority());
    }
}
