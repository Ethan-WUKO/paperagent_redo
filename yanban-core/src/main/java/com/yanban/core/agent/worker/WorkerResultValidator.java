package com.yanban.core.agent.worker;

/** Deterministic fail-closed validation before a WorkerResult can be aggregated. */
public final class WorkerResultValidator {
    private WorkerResultValidator() { }

    public static WorkerResultAttestation attest(WorkerTaskAttestation taskAttestation,
                                                  WorkerExecutionReceipt executionReceipt,
                                                  WorkerResult result) {
        if (taskAttestation == null || executionReceipt == null || result == null) {
            throw new IllegalArgumentException(
                    "worker task attestation, execution receipt, and result are required");
        }
        if (!executionReceipt.belongsTo(taskAttestation)) {
            throw new IllegalArgumentException("execution receipt belongs to a different worker task");
        }
        result.validateAgainst(taskAttestation.packet());
        result.validateAgainstReceipt(taskAttestation.packet(), executionReceipt);
        return new WorkerResultAttestation(taskAttestation, executionReceipt, result);
    }
}
