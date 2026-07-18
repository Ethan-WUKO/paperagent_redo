package com.yanban.core.agent.worker;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/** Non-serializable proof that a result passed its exact task boundary. */
@JsonSerialize(using = WorkerServerOnlySerializer.class)
@JsonDeserialize(using = WorkerServerOnlyDeserializer.class)
public final class WorkerResultAttestation {
    private final WorkerTaskAttestation taskAttestation;
    private final WorkerExecutionReceipt executionReceipt;
    private final WorkerResult result;

    WorkerResultAttestation(WorkerTaskAttestation taskAttestation,
                            WorkerExecutionReceipt executionReceipt,
                            WorkerResult result) {
        this.taskAttestation = taskAttestation;
        this.executionReceipt = executionReceipt;
        this.result = result;
    }

    public WorkerResult result() { return result; }
    public WorkerTaskPacket taskPacket() { return taskAttestation.packet(); }
    public WorkerAccessMode accessMode() { return WorkerAccessMode.READ_ONLY; }
    public boolean canWriteCandidate() { return false; }
    public boolean canApplyRevision() { return false; }
    public boolean canExecuteCommands() { return false; }
    public boolean canUseNetwork() { return false; }
    public boolean canCompleteParentTask() { return false; }

    WorkerServerAuthority authority() { return taskAttestation.authority(); }
    WorkerExecutionReceipt executionReceipt() { return executionReceipt; }
}
