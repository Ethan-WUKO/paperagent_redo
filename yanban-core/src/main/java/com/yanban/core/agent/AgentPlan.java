package com.yanban.core.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_plans")
public class AgentPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "goal", nullable = false, columnDefinition = "LONGTEXT")
    private String goal;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "rag_disabled", nullable = false)
    private Boolean ragDisabled;

    @Column(name = "skill_id", length = 128)
    private String skillId;

    @Column(name = "raw_plan_json", columnDefinition = "LONGTEXT")
    private String rawPlanJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "persistence_level", nullable = false, length = 32)
    private String persistenceLevel;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "lease_fence", nullable = false)
    private Long leaseFence;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    @Column(name = "checkpoint_json", columnDefinition = "LONGTEXT")
    private String checkpointJson;

    @Column(name = "checkpoint_hash", length = 64)
    private String checkpointHash;

    @Column(name = "checkpoint_version", nullable = false)
    private Long checkpointVersion;

    @Column(name = "recovery_status", nullable = false, length = 32)
    private String recoveryStatus;

    @Column(name = "canonical_answer", columnDefinition = "LONGTEXT")
    private String canonicalAnswer;

    @Column(name = "canonical_answer_hash", length = 64)
    private String canonicalAnswerHash;

    protected AgentPlan() {
    }

    public AgentPlan(Long sessionId, Long userId, String goal, String summary,
                     Boolean ragDisabled, String skillId, String rawPlanJson) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.goal = goal;
        this.summary = summary;
        this.status = AgentPlanStatus.REVIEWING.name();
        this.ragDisabled = Boolean.TRUE.equals(ragDisabled);
        this.skillId = skillId;
        this.rawPlanJson = rawPlanJson;
        this.persistenceLevel = "L1_PERSISTED";
        this.leaseFence = 0L;
        this.checkpointVersion = 0L;
        this.recoveryStatus = "NONE";
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.persistenceLevel == null) this.persistenceLevel = "L1_PERSISTED";
        if (this.leaseFence == null) this.leaseFence = 0L;
        if (this.checkpointVersion == null) this.checkpointVersion = 0L;
        if (this.recoveryStatus == null) this.recoveryStatus = "NONE";
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public String getGoal() { return goal; }
    public String getSummary() { return summary; }
    public String getStatus() { return status; }
    public Boolean getRagDisabled() { return ragDisabled; }
    public String getSkillId() { return skillId; }
    public String getRawPlanJson() { return rawPlanJson; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public String getPersistenceLevel() { return persistenceLevel; }
    public String getLeaseOwner() { return leaseOwner; }
    public String getLeaseToken() { return leaseToken; }
    public Long getLeaseFence() { return leaseFence; }
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public LocalDateTime getHeartbeatAt() { return heartbeatAt; }
    public String getCheckpointJson() { return checkpointJson; }
    public String getCheckpointHash() { return checkpointHash; }
    public Long getCheckpointVersion() { return checkpointVersion; }
    public String getRecoveryStatus() { return recoveryStatus; }
    public String getCanonicalAnswer() { return canonicalAnswer; }
    public String getCanonicalAnswerHash() { return canonicalAnswerHash; }

    public void enableDurableExecution() {
        this.persistenceLevel = "L2_DURABLE";
    }

    public void markRunning() {
        this.status = AgentPlanStatus.RUNNING.name();
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
        this.errorMessage = null;
    }

    public void markPaused() {
        this.status = AgentPlanStatus.PAUSED.name();
    }

    public void markCompleted() {
        this.status = AgentPlanStatus.COMPLETED.name();
        this.finishedAt = LocalDateTime.now();
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.status = AgentPlanStatus.FAILED.name();
        this.finishedAt = LocalDateTime.now();
        this.errorMessage = errorMessage;
    }

    public void markCancelled(String reason) {
        this.status = AgentPlanStatus.CANCELLED.name();
        this.finishedAt = LocalDateTime.now();
        this.errorMessage = reason;
    }

    public void resetForRetry() {
        this.status = AgentPlanStatus.REVIEWING.name();
        this.errorMessage = null;
        this.startedAt = null;
        this.finishedAt = null;
        this.recoveryStatus = "RETRY_QUEUED";
        clearCanonicalAnswerForExplicitRetry();
    }

    /** Explicit durable retry keeps the original elapsed-time budget and checkpoint history. */
    public void resetForDurableRetry() {
        this.status = AgentPlanStatus.REVIEWING.name();
        this.errorMessage = null;
        this.finishedAt = null;
        this.recoveryStatus = "RETRY_QUEUED";
        clearCanonicalAnswerForExplicitRetry();
    }

    public boolean terminal() {
        return AgentPlanStatus.COMPLETED.name().equals(status)
                || AgentPlanStatus.FAILED.name().equals(status)
                || AgentPlanStatus.CANCELLED.name().equals(status);
    }

    boolean durableExecution() {
        return "L2_DURABLE".equals(persistenceLevel);
    }

    boolean leaseActiveAt(LocalDateTime databaseNow) {
        return leaseOwner != null && leaseToken != null && leaseExpiresAt != null
                && leaseExpiresAt.isAfter(databaseNow);
    }

    boolean leaseMatches(String owner, String token, long fence) {
        return owner != null && owner.equals(leaseOwner)
                && token != null && token.equals(leaseToken)
                && leaseFence != null && leaseFence == fence;
    }

    void claimLease(String owner, String token, LocalDateTime databaseNow,
                    LocalDateTime leaseUntil, boolean recovery) {
        this.leaseOwner = owner;
        this.leaseToken = token;
        this.leaseFence = (leaseFence == null ? 0L : leaseFence) + 1L;
        this.heartbeatAt = databaseNow;
        this.leaseExpiresAt = leaseUntil;
        this.recoveryStatus = recovery ? "RECOVERING" : "CLAIMED";
        markRunning();
    }

    void queueForExecution() {
        markRunning();
        this.recoveryStatus = "QUEUED";
    }

    void renewLease(LocalDateTime databaseNow, LocalDateTime leaseUntil) {
        this.heartbeatAt = databaseNow;
        this.leaseExpiresAt = leaseUntil;
        this.recoveryStatus = "RUNNING";
    }

    void releaseLease(String status) {
        this.leaseOwner = null;
        this.leaseToken = null;
        this.leaseExpiresAt = null;
        this.heartbeatAt = null;
        this.recoveryStatus = status;
    }

    void storeCheckpoint(String json, String hash, long version) {
        this.checkpointJson = json;
        this.checkpointHash = hash;
        this.checkpointVersion = version;
        this.recoveryStatus = "CHECKPOINTED";
    }

    public void publishCanonicalAnswer(String answer, String hash) {
        if (answer == null || answer.isBlank()) return;
        if (hash == null || hash.length() != 64) {
            throw new IllegalArgumentException("canonical answer digest is invalid");
        }
        if (canonicalAnswer == null) {
            canonicalAnswer = answer;
            canonicalAnswerHash = hash;
            return;
        }
        if (!canonicalAnswer.equals(answer) || !java.util.Objects.equals(canonicalAnswerHash, hash)) {
            throw new IllegalStateException("canonical answer is already published");
        }
    }

    private void clearCanonicalAnswerForExplicitRetry() {
        this.canonicalAnswer = null;
        this.canonicalAnswerHash = null;
    }

    void copyLifecycleFrom(AgentPlan source) {
        this.status = source.status;
        this.errorMessage = source.errorMessage;
        this.startedAt = source.startedAt;
        this.finishedAt = source.finishedAt;
    }
}
