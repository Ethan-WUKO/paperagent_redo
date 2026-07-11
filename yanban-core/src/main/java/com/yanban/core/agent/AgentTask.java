package com.yanban.core.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "agent_tasks")
public class AgentTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "task_type", nullable = false, length = 64)
    private String taskType;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 64)
    private String strategy;

    @Column(name = "client_request_id", length = 128)
    private String clientRequestId;

    @Column(length = 255)
    private String title;

    @Column(name = "input_summary", length = 1000)
    private String inputSummary;

    @Column(name = "progress_percent")
    private Integer progressPercent;

    @Column(name = "current_stage", length = 64)
    private String currentStage;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "max_retries")
    private Integer maxRetries;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentTask() {
    }

    public AgentTask(Long userId, String taskType, String source, Long sourceId, String status) {
        this.userId = userId;
        this.taskType = taskType;
        this.source = source;
        this.sourceId = sourceId;
        this.status = status;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getProjectId() { return projectId; }
    public String getTaskType() { return taskType; }
    public String getSource() { return source; }
    public Long getSourceId() { return sourceId; }
    public String getStatus() { return status; }
    public String getStrategy() { return strategy; }
    public String getClientRequestId() { return clientRequestId; }
    public String getTitle() { return title; }
    public String getInputSummary() { return inputSummary; }
    public Integer getProgressPercent() { return progressPercent; }
    public String getCurrentStage() { return currentStage; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getCancellationReason() { return cancellationReason; }
    public Integer getRetryCount() { return retryCount; }
    public Integer getMaxRetries() { return maxRetries; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setUserId(Long userId) { this.userId = userId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public void setSource(String source) { this.source = source; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public void setStatus(String status) { this.status = status; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public void setTitle(String title) { this.title = title; }
    public void setInputSummary(String inputSummary) { this.inputSummary = inputSummary; }
    public void setProgressPercent(Integer progressPercent) { this.progressPercent = progressPercent; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setCancellationReason(String cancellationReason) { this.cancellationReason = cancellationReason; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
