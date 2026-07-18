package com.yanban.core.agent.worker;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/** Non-serializable proof that a server authority accepted one packet. */
@JsonSerialize(using = WorkerServerOnlySerializer.class)
@JsonDeserialize(using = WorkerServerOnlyDeserializer.class)
public final class WorkerTaskAttestation {
    private final WorkerServerAuthority authority;
    private final WorkerTaskPacket packet;

    WorkerTaskAttestation(WorkerServerAuthority authority, WorkerTaskPacket packet) {
        this.authority = authority;
        this.packet = packet;
    }

    public WorkerTaskPacket packet() { return packet; }
    public WorkerAccessMode accessMode() { return WorkerAccessMode.READ_ONLY; }
    public boolean canWriteCandidate() { return false; }
    public boolean canApplyRevision() { return false; }
    public boolean canExecuteCommands() { return false; }
    public boolean canUseNetwork() { return false; }
    public boolean canCompleteParentTask() { return false; }

    WorkerServerAuthority authority() { return authority; }
}
