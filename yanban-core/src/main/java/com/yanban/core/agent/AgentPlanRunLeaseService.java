package com.yanban.core.agent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serializes durable Plan ownership and fenced writes using database time and row locks. */
@Service
public class AgentPlanRunLeaseService {

    private final AgentPlanRepository plans;
    private final AgentPlanStepRepository steps;
    private final AgentPlanEventRepository events;

    public AgentPlanRunLeaseService(AgentPlanRepository plans, AgentPlanStepRepository steps,
                                    AgentPlanEventRepository events) {
        this.plans = plans;
        this.steps = steps;
        this.events = events;
    }

    @Transactional
    public Optional<AgentPlanExecutionLease> claim(Long planId, Long userId, String owner,
                                                    Duration duration) {
        requireDuration(duration);
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("lease owner is required");
        AgentPlan plan = plans.findLockedByIdAndUserId(planId, userId).orElse(null);
        if (plan == null || !plan.durableExecution() || plan.terminal()
                || AgentPlanStatus.PAUSED.name().equals(plan.getStatus())
                || "STALE".equals(plan.getRecoveryStatus())) {
            return Optional.empty();
        }
        LocalDateTime now = databaseNow(planId);
        if (plan.leaseActiveAt(now)) return Optional.empty();
        boolean recovery = (AgentPlanStatus.RUNNING.name().equals(plan.getStatus())
                && !"QUEUED".equals(plan.getRecoveryStatus()))
                || (plan.getCheckpointVersion() != null && plan.getCheckpointVersion() > 0);
        String token = UUID.randomUUID().toString().replace("-", "");
        plan.claimLease(owner.trim(), token, now, now.plus(duration), recovery);
        plans.saveAndFlush(plan);
        return Optional.of(new AgentPlanExecutionLease(planId, userId, owner.trim(), token,
                plan.getLeaseFence(), plan.getLeaseExpiresAt(), recovery));
    }

    @Transactional
    public boolean renew(AgentPlanExecutionLease lease, Duration duration) {
        requireDuration(duration);
        AgentPlan plan = locked(lease);
        LocalDateTime now = databaseNow(lease.planId());
        if (!owned(plan, lease) || plan.terminal() || !plan.leaseActiveAt(now)) return false;
        plan.renewLease(now, now.plus(duration));
        plans.saveAndFlush(plan);
        return true;
    }

    @Transactional
    public boolean queue(Long planId, Long userId) {
        AgentPlan plan = plans.findLockedByIdAndUserId(planId, userId).orElse(null);
        if (plan == null || !plan.durableExecution() || plan.terminal()
                || AgentPlanStatus.PAUSED.name().equals(plan.getStatus())
                || "STALE".equals(plan.getRecoveryStatus())) {
            return false;
        }
        LocalDateTime now = databaseNow(planId);
        if (AgentPlanStatus.RUNNING.name().equals(plan.getStatus()) || plan.leaseActiveAt(now)) return false;
        plan.queueForExecution();
        plans.saveAndFlush(plan);
        return true;
    }

    @Transactional
    public void assertOwned(AgentPlanExecutionLease lease) {
        AgentPlan plan = locked(lease);
        LocalDateTime now = databaseNow(lease.planId());
        if (!owned(plan, lease) || plan.terminal() || !plan.leaseActiveAt(now)) {
            throw lost(lease);
        }
    }

    @Transactional
    public AgentPlan saveOwnedPlan(AgentPlanExecutionLease lease, AgentPlan source) {
        AgentPlan locked = requireActive(lease);
        locked.copyLifecycleFrom(source);
        return plans.saveAndFlush(locked);
    }

    @Transactional
    public AgentPlanStep saveOwnedStep(AgentPlanExecutionLease lease, AgentPlanStep step) {
        requireActive(lease);
        if (step == null || !lease.planId().equals(step.getPlanId())) {
            throw new IllegalArgumentException("step does not belong to the leased Plan");
        }
        return steps.saveAndFlush(step);
    }

    @Transactional
    public boolean saveOwnedEvent(AgentPlanExecutionLease lease, AgentPlanEvent event) {
        requireOwnedLease(lease, true);
        if (event == null || !lease.planId().equals(event.getPlanId())) {
            throw new IllegalArgumentException("event does not belong to the leased Plan");
        }
        if (event.getIdempotencyKey() != null) {
            Optional<AgentPlanEvent> existing = events.findByPlanIdAndIdempotencyKey(
                    event.getPlanId(), event.getIdempotencyKey());
            if (existing != null && existing.isPresent()) return false;
        }
        events.saveAndFlush(event);
        return true;
    }

    @Transactional
    public AgentPlan storeCheckpoint(AgentPlanExecutionLease lease, String checkpointJson,
                                     String checkpointHash, long checkpointVersion) {
        AgentPlan plan = requireActive(lease);
        long expected = (plan.getCheckpointVersion() == null ? 0L : plan.getCheckpointVersion()) + 1L;
        if (checkpointVersion != expected || checkpointJson == null || checkpointJson.isBlank()
                || checkpointHash == null || checkpointHash.length() != 64) {
            throw new IllegalArgumentException("checkpoint version or digest is invalid");
        }
        plan.storeCheckpoint(checkpointJson, checkpointHash, checkpointVersion);
        return plans.saveAndFlush(plan);
    }

    @Transactional
    public AgentPlan finish(AgentPlanExecutionLease lease, AgentPlan source,
                            String canonicalAnswer, String canonicalHash, String recoveryStatus) {
        if (source == null || !source.terminal()) {
            throw new IllegalArgumentException("durable Plan finish requires a terminal lifecycle");
        }
        if (canonicalAnswer == null || canonicalAnswer.isBlank()
                || canonicalHash == null || canonicalHash.length() != 64) {
            throw new IllegalArgumentException("terminal durable Plan requires one canonical answer and digest");
        }
        AgentPlan plan = requireOwnedLease(lease, true);
        plan.copyLifecycleFrom(source);
        plan.publishCanonicalAnswer(canonicalAnswer, canonicalHash);
        plan.releaseLease(recoveryStatus == null ? "TERMINAL" : recoveryStatus);
        return plans.saveAndFlush(plan);
    }

    /** Publishes the one terminal answer for cancellation/recovery paths that no longer own a run lease. */
    @Transactional
    public AgentPlan publishTerminalCanonical(Long planId, Long userId, String answer, String hash) {
        AgentPlan plan = plans.findLockedByIdAndUserId(planId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Plan does not exist"));
        if (!plan.terminal()) {
            throw new IllegalStateException("canonical answer requires a terminal Plan");
        }
        // Recovery readers may race with the normal terminal publisher. The row lock makes the
        // first published canonical authoritative; every later publisher converges on that value
        // instead of overwriting it or surfacing an immutable-canonical exception.
        if (plan.getCanonicalAnswer() != null && !plan.getCanonicalAnswer().isBlank()) {
            return plan;
        }
        plan.publishCanonicalAnswer(answer, hash);
        return plans.saveAndFlush(plan);
    }

    @Transactional
    public void release(AgentPlanExecutionLease lease, String recoveryStatus) {
        AgentPlan plan = locked(lease);
        if (!owned(plan, lease)) return;
        plan.releaseLease(recoveryStatus == null ? "RELEASED" : recoveryStatus);
        plans.saveAndFlush(plan);
    }

    @Transactional
    public AgentPlan rejectRecovery(AgentPlanExecutionLease lease, String reason) {
        AgentPlan plan = requireActive(lease);
        plan.markFailed(reason);
        plan.releaseLease("RECOVERY_REJECTED");
        return plans.saveAndFlush(plan);
    }

    @Transactional
    public AgentPlan cancel(Long planId, Long userId, String reason) {
        AgentPlan plan = plans.findLockedByIdAndUserId(planId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Plan does not exist"));
        if (!plan.terminal()) {
            plan.markCancelled(reason);
            plan.releaseLease("CANCELLED");
            plans.saveAndFlush(plan);
        }
        return plan;
    }

    @Transactional(readOnly = true)
    public List<RecoverableRun> expiredRuns() {
        return plans.findExpiredDurableRuns().stream()
                .map(plan -> new RecoverableRun(plan.getId(), plan.getUserId()))
                .toList();
    }

    private AgentPlan requireActive(AgentPlanExecutionLease lease) {
        return requireOwnedLease(lease, false);
    }

    private AgentPlan requireOwnedLease(AgentPlanExecutionLease lease, boolean allowTerminal) {
        AgentPlan plan = locked(lease);
        LocalDateTime now = databaseNow(lease.planId());
        if (!owned(plan, lease) || (!allowTerminal && plan.terminal()) || !plan.leaseActiveAt(now)) {
            throw lost(lease);
        }
        return plan;
    }

    private AgentPlan locked(AgentPlanExecutionLease lease) {
        if (lease == null) throw new IllegalArgumentException("execution lease is required");
        return plans.findLockedByIdAndUserId(lease.planId(), lease.userId()).orElseThrow(() -> lost(lease));
    }

    private boolean owned(AgentPlan plan, AgentPlanExecutionLease lease) {
        return plan.leaseMatches(lease.owner(), lease.token(), lease.fence());
    }

    private LocalDateTime databaseNow(Long planId) {
        LocalDateTime now = plans.currentDatabaseTime(planId);
        if (now == null) throw new IllegalStateException("database time is unavailable");
        return now;
    }

    private void requireDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()
                || duration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("lease duration is invalid");
        }
    }

    private AgentPlanLeaseLostException lost(AgentPlanExecutionLease lease) {
        return new AgentPlanLeaseLostException("execution lease is no longer owned for Plan " + lease.planId());
    }

    public record RecoverableRun(Long planId, Long userId) { }
}
