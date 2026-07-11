package com.yanban.paper.domain;

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
@Table(name = "paper_tasks")
public class PaperTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "source_filename", length = 255)
    private String sourceFilename;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "final_object_key", length = 512)
    private String finalObjectKey;

    @Column(name = "client_request_id", length = 128)
    private String clientRequestId;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "input_format", length = 16)
    private String inputFormat;

    @Column(length = 32)
    private String mode;

    @Column(name = "main_entry", length = 512)
    private String mainEntry;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "target_language", nullable = false, length = 16)
    private String targetLanguage;

    @Column(name = "current_stage", length = 64)
    private String currentStage;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "literature_min_count")
    private Integer literatureMinCount;

    @Column(name = "literature_count")
    private Integer literatureCount;

    @Column(name = "score_threshold", nullable = false)
    private Integer scoreThreshold = 80;

    @Column(name = "max_rounds", nullable = false)
    private Integer maxRounds = 3;

    @Column(name = "inner_max_attempts", nullable = false)
    private Integer innerMaxAttempts = 2;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaperTask() {
    }

    public PaperTask(Long userId, String title, String sourceFilename, String objectKey,
                     String status, String targetLanguage, String currentStage, String errorMessage) {
        this(userId, title, sourceFilename, objectKey, status, targetLanguage, currentStage, errorMessage, null, null);
    }

    public PaperTask(Long userId, String title, String sourceFilename, String objectKey,
                     String status, String targetLanguage, String currentStage, String errorMessage,
                     String clientRequestId, String idempotencyKey) {
        this.userId = userId;
        this.title = title;
        this.sourceFilename = sourceFilename;
        this.objectKey = objectKey;
        this.status = status;
        this.targetLanguage = targetLanguage;
        this.currentStage = currentStage;
        this.errorMessage = errorMessage;
        this.clientRequestId = clientRequestId;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getSourceFilename() { return sourceFilename; }
    public String getObjectKey() { return objectKey; }
    public String getFinalObjectKey() { return finalObjectKey; }
    public String getClientRequestId() { return clientRequestId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getInputFormat() { return inputFormat; }
    public String getMode() { return mode; }
    public String getMainEntry() { return mainEntry; }
    public String getStatus() { return status; }
    public String getTargetLanguage() { return targetLanguage; }
    public String getCurrentStage() { return currentStage; }
    public String getErrorMessage() { return errorMessage; }
    public Integer getLiteratureMinCount() { return literatureMinCount; }
    public Integer getLiteratureCount() { return literatureCount; }
    public Integer getScoreThreshold() { return scoreThreshold; }
    public Integer getMaxRounds() { return maxRounds; }
    public Integer getInnerMaxAttempts() { return innerMaxAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setFinalObjectKey(String finalObjectKey) { this.finalObjectKey = finalObjectKey; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public void setInputFormat(String inputFormat) { this.inputFormat = inputFormat; }
    public void setMode(String mode) { this.mode = mode; }
    public void setMainEntry(String mainEntry) { this.mainEntry = mainEntry; }
    public void setStatus(String status) { this.status = status; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setLiteratureMinCount(Integer literatureMinCount) { this.literatureMinCount = literatureMinCount; }
    public void setLiteratureCount(Integer literatureCount) { this.literatureCount = literatureCount; }
    public void setScoreThreshold(Integer scoreThreshold) { this.scoreThreshold = scoreThreshold; }
    public void setMaxRounds(Integer maxRounds) { this.maxRounds = maxRounds; }
    public void setInnerMaxAttempts(Integer innerMaxAttempts) { this.innerMaxAttempts = innerMaxAttempts; }
}
