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
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
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
    }

    public boolean terminal() {
        return AgentPlanStatus.COMPLETED.name().equals(status)
                || AgentPlanStatus.FAILED.name().equals(status)
                || AgentPlanStatus.CANCELLED.name().equals(status);
    }
}
