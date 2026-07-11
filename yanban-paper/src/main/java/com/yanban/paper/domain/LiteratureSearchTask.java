package com.yanban.paper.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "literature_search_tasks")
public class LiteratureSearchTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false, length = 512)
    private String query;

    @Column(name = "normalized_query", nullable = false, length = 512)
    private String normalizedQuery;

    @Column(name = "top_k", nullable = false)
    private Integer topK;

    @Column(name = "year_from")
    private Integer yearFrom;

    @Column(name = "include_bibtex", nullable = false)
    private Boolean includeBibtex;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "current_stage", length = 64)
    private String currentStage;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "raw_candidate_count")
    private Integer rawCandidateCount;

    @Column(name = "unique_candidate_count")
    private Integer uniqueCandidateCount;

    @Column(name = "source_attempts")
    private Integer sourceAttempts;

    @Lob
    @Column(name = "source_failures_json")
    private String sourceFailuresJson;

    @Lob
    @Column(name = "result_json")
    private String resultJson;

    @Column(name = "client_request_id", nullable = false, length = 128)
    private String clientRequestId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

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

    protected LiteratureSearchTask() {
    }

    public LiteratureSearchTask(Long userId,
                                Long projectId,
                                String query,
                                String normalizedQuery,
                                Integer topK,
                                Integer yearFrom,
                                Boolean includeBibtex,
                                String status,
                                String currentStage,
                                String clientRequestId,
                                String idempotencyKey) {
        this.userId = userId;
        this.projectId = projectId;
        this.query = query;
        this.normalizedQuery = normalizedQuery;
        this.topK = topK;
        this.yearFrom = yearFrom;
        this.includeBibtex = includeBibtex;
        this.status = status;
        this.currentStage = currentStage;
        this.clientRequestId = clientRequestId;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getProjectId() { return projectId; }
    public String getQuery() { return query; }
    public String getNormalizedQuery() { return normalizedQuery; }
    public Integer getTopK() { return topK; }
    public Integer getYearFrom() { return yearFrom; }
    public Boolean getIncludeBibtex() { return includeBibtex; }
    public String getStatus() { return status; }
    public String getCurrentStage() { return currentStage; }
    public String getErrorMessage() { return errorMessage; }
    public Integer getRawCandidateCount() { return rawCandidateCount; }
    public Integer getUniqueCandidateCount() { return uniqueCandidateCount; }
    public Integer getSourceAttempts() { return sourceAttempts; }
    public String getSourceFailuresJson() { return sourceFailuresJson; }
    public String getResultJson() { return resultJson; }
    public String getClientRequestId() { return clientRequestId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCancelReason() { return cancelReason; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public void setStatus(String status) { this.status = status; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setRawCandidateCount(Integer rawCandidateCount) { this.rawCandidateCount = rawCandidateCount; }
    public void setUniqueCandidateCount(Integer uniqueCandidateCount) { this.uniqueCandidateCount = uniqueCandidateCount; }
    public void setSourceAttempts(Integer sourceAttempts) { this.sourceAttempts = sourceAttempts; }
    public void setSourceFailuresJson(String sourceFailuresJson) { this.sourceFailuresJson = sourceFailuresJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
