package com.yanban.core.agent.worker;

/** Validates an authority-free packet against trusted parent policy. */
public final class WorkerTaskAttestor {
    private WorkerTaskAttestor() { }

    public static WorkerTaskAttestation attestServerResolved(WorkerServerAuthority authority,
                                                              WorkerTaskPacket packet) {
        if (authority == null || packet == null) {
            throw new IllegalArgumentException("worker authority and task packet are required");
        }
        if (!authority.parentRunId().equals(packet.parentRunId())) {
            throw new IllegalArgumentException("worker packet belongs to a different parent run");
        }
        if (!authority.projectVersion().equals(packet.projectVersion())) {
            throw new IllegalArgumentException("worker packet does not use the current Project version");
        }
        if (!authority.parentAllowedReadTools().containsAll(packet.allowedReadTools())) {
            throw new IllegalArgumentException("worker packet tools exceed the parent allowlist");
        }
        if (!packet.budget().fitsWithin(authority.parentBudget())) {
            throw new IllegalArgumentException("worker packet budget exceeds the parent budget");
        }
        return new WorkerTaskAttestation(authority, packet);
    }
}
