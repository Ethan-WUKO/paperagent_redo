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
@Table(name = "suggestions")
public class Suggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "section_id")
    private Long sectionId;

    @Column(nullable = false, length = 32)
    private String track;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(length = 32)
    private String severity;

    @Column(nullable = false, length = 4000)
    private String statement;

    @Column(nullable = false)
    private Boolean applicable = false;

    @Column(name = "patch_json", length = 10000)
    private String patchJson;

    @Column(nullable = false, length = 32)
    private String status = "PROPOSED";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Suggestion() {
    }

    public Suggestion(Long taskId, String track, String category, String statement) {
        this.taskId = taskId;
        this.track = track;
        this.category = category;
        this.statement = statement;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public Long getSectionId() { return sectionId; }
    public String getTrack() { return track; }
    public String getCategory() { return category; }
    public String getSeverity() { return severity; }
    public String getStatement() { return statement; }
    public Boolean getApplicable() { return applicable; }
    public String getPatchJson() { return patchJson; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }
    public void setTrack(String track) { this.track = track; }
    public void setCategory(String category) { this.category = category; }
    public void setSeverity(String severity) { this.severity = severity; }
    public void setStatement(String statement) { this.statement = statement; }
    public void setApplicable(Boolean applicable) { this.applicable = applicable; }
    public void setPatchJson(String patchJson) { this.patchJson = patchJson; }
    public void setStatus(String status) { this.status = status; }
}
