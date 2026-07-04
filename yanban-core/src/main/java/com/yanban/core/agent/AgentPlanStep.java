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
@Table(name = "agent_plan_steps")
public class AgentPlanStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "step_key", nullable = false, length = 64)
    private String stepKey;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "LONGTEXT")
    private String description;

    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Column(name = "dependencies_json", columnDefinition = "LONGTEXT")
    private String dependenciesJson;

    @Column(name = "allowed_tools_json", columnDefinition = "LONGTEXT")
    private String allowedToolsJson;

    @Column(name = "success_criteria", columnDefinition = "LONGTEXT")
    private String successCriteria;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "result", columnDefinition = "LONGTEXT")
    private String result;

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

    protected AgentPlanStep() {
    }

    public AgentPlanStep(Long planId, String stepKey, Integer sortOrder, String title,
                         String description, String type, String dependenciesJson,
                         String allowedToolsJson, String successCriteria) {
        this.planId = planId;
        this.stepKey = stepKey;
        this.sortOrder = sortOrder;
        this.title = title;
        this.description = description;
        this.type = type;
        this.dependenciesJson = dependenciesJson;
        this.allowedToolsJson = allowedToolsJson;
        this.successCriteria = successCriteria;
        this.status = AgentPlanStepStatus.PENDING.name();
        this.attemptCount = 0;
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
    public Long getPlanId() { return planId; }
    public String getStepKey() { return stepKey; }
    public Integer getSortOrder() { return sortOrder; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getType() { return type; }
    public String getDependenciesJson() { return dependenciesJson; }
    public String getAllowedToolsJson() { return allowedToolsJson; }
    public String getSuccessCriteria() { return successCriteria; }
    public String getStatus() { return status; }
    public Integer getAttemptCount() { return attemptCount; }
    public String getResult() { return result; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }

    public void markRunning() {
        this.status = AgentPlanStepStatus.RUNNING.name();
        this.attemptCount = this.attemptCount == null ? 1 : this.attemptCount + 1;
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
        this.errorMessage = null;
    }

    public void markCompleted(String result) {
        this.status = AgentPlanStepStatus.COMPLETED.name();
        this.result = result;
        this.errorMessage = null;
        this.finishedAt = LocalDateTime.now();
    }

    public void markRepairing(String reason) {
        this.status = AgentPlanStepStatus.REPAIRING.name();
        this.errorMessage = reason;
        this.finishedAt = null;
    }

    public void markDegraded(String result, String warning) {
        this.status = AgentPlanStepStatus.DEGRADED.name();
        this.result = result;
        this.errorMessage = warning;
        this.finishedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = AgentPlanStepStatus.FAILED.name();
        this.errorMessage = errorMessage;
        this.finishedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage, String partialResult) {
        this.status = AgentPlanStepStatus.FAILED.name();
        this.errorMessage = errorMessage;
        this.result = partialResult;
        this.finishedAt = LocalDateTime.now();
    }

    public void markSkipped(String reason) {
        this.status = AgentPlanStepStatus.SKIPPED.name();
        this.errorMessage = reason;
        this.finishedAt = LocalDateTime.now();
    }

    public void markSuperseded(String reason) {
        this.status = AgentPlanStepStatus.SUPERSEDED.name();
        this.errorMessage = reason;
        this.finishedAt = LocalDateTime.now();
    }

    public void resetForRetry() {
        this.status = AgentPlanStepStatus.PENDING.name();
        this.attemptCount = 0;
        this.result = null;
        this.errorMessage = null;
        this.startedAt = null;
        this.finishedAt = null;
    }

    public void updateDependenciesJson(String dependenciesJson) {
        this.dependenciesJson = dependenciesJson;
    }

    public void updateSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
