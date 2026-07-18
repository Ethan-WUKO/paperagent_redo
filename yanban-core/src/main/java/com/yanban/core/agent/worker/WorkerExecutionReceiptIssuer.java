package com.yanban.core.agent.worker;

import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ResearchEvidenceRef;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Contract boundary for a future trusted execution adapter; no executor is implemented here. */
public final class WorkerExecutionReceiptIssuer {
    private WorkerExecutionReceiptIssuer() { }

    public static WorkerExecutionReceipt recordServerExecution(
            WorkerTaskAttestation taskAttestation,
            List<ProjectRelativePath> inspectedPaths,
            List<String> executedTools,
            List<ResearchEvidenceRef> observedEvidence,
            long bytesInspected,
            int toolCallCount) {
        if (taskAttestation == null) {
            throw new IllegalArgumentException("task attestation is required for an execution receipt");
        }
        WorkerTaskPacket packet = taskAttestation.packet();
        List<ProjectRelativePath> paths = WorkerContractSupport.sortedDistinctPaths(inspectedPaths,
                WorkerTaskPacket.MAX_MATERIAL_PATHS, "execution receipt inspected paths");
        if (!new HashSet<>(packet.materialScope()).containsAll(paths)) {
            throw new IllegalArgumentException("execution receipt inspected a path outside the task assignment");
        }
        List<String> tools = WorkerContractSupport.sortedDistinctTools(executedTools,
                WorkerTaskPacket.MAX_ALLOWED_TOOLS);
        if (!packet.allowedReadTools().containsAll(tools)) {
            throw new IllegalArgumentException("execution receipt contains a tool outside the task allowlist");
        }
        List<ResearchEvidenceRef> evidence = WorkerContractSupport.sortedDistinctEvidence(observedEvidence,
                WorkerTaskPacket.MAX_EVIDENCE_REFS, "execution receipt evidence");
        WorkerContractSupport.requireEvidenceScope(packet.projectVersion(), packet.materialScope(), evidence,
                "execution receipt evidence");
        Set<ProjectRelativePath> inspected = new HashSet<>(paths);
        if (evidence.stream().anyMatch(item -> !inspected.contains(item.relativePath()))) {
            throw new IllegalArgumentException("execution receipt evidence was not observed on an inspected path");
        }
        if (bytesInspected < 0 || toolCallCount < tools.size()
                || (toolCallCount == 0) != tools.isEmpty()
                || (!paths.isEmpty() && toolCallCount == 0)) {
            throw new IllegalArgumentException("execution receipt counts are inconsistent with its facts");
        }
        packet.budget().validate(new WorkerBudgetUsage(paths.size(), toolCallCount, 0,
                evidence.size(), bytesInspected, 0));
        return new WorkerExecutionReceipt(taskAttestation, paths, tools, evidence,
                bytesInspected, toolCallCount);
    }
}
