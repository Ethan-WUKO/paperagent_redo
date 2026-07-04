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
@Table(name = "paper_sections")
public class PaperSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "source_path", length = 512)
    private String sourcePath;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(nullable = false)
    private Integer level;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false, length = 64)
    private String role;

    @Column(name = "role_confidence")
    private Double roleConfidence;

    @Column(name = "role_source", length = 32)
    private String roleSource;

    @Column(name = "char_start", nullable = false)
    private Integer charStart;

    @Column(name = "char_end", nullable = false)
    private Integer charEnd;

    @Column(name = "original_object_key", length = 512)
    private String originalObjectKey;

    @Column(name = "polished_object_key", length = 512)
    private String polishedObjectKey;

    @Column(name = "review_json", length = 10000)
    private String reviewJson;

    @Column(name = "diff_json", length = 10000)
    private String diffJson;

    @Column(name = "polish_status", length = 32)
    private String polishStatus;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaperSection() {
    }

    public PaperSection(Long taskId, String sourcePath, Integer orderIndex, Integer level,
                        String title, String role, Double roleConfidence, String roleSource,
                        Integer charStart, Integer charEnd) {
        this.taskId = taskId;
        this.sourcePath = sourcePath;
        this.orderIndex = orderIndex;
        this.level = level;
        this.title = title;
        this.role = role;
        this.roleConfidence = roleConfidence;
        this.roleSource = roleSource;
        this.charStart = charStart;
        this.charEnd = charEnd;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public String getSourcePath() { return sourcePath; }
    public Integer getOrderIndex() { return orderIndex; }
    public Integer getLevel() { return level; }
    public String getTitle() { return title; }
    public String getRole() { return role; }
    public Double getRoleConfidence() { return roleConfidence; }
    public String getRoleSource() { return roleSource; }
    public Integer getCharStart() { return charStart; }
    public Integer getCharEnd() { return charEnd; }
    public String getOriginalObjectKey() { return originalObjectKey; }
    public String getPolishedObjectKey() { return polishedObjectKey; }
    public String getReviewJson() { return reviewJson; }
    public String getDiffJson() { return diffJson; }
    public String getPolishStatus() { return polishStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setRole(String role) { this.role = role; }
    public void setRoleConfidence(Double roleConfidence) { this.roleConfidence = roleConfidence; }
    public void setRoleSource(String roleSource) { this.roleSource = roleSource; }
    public void setOriginalObjectKey(String originalObjectKey) { this.originalObjectKey = originalObjectKey; }
    public void setPolishedObjectKey(String polishedObjectKey) { this.polishedObjectKey = polishedObjectKey; }
    public void setReviewJson(String reviewJson) { this.reviewJson = reviewJson; }
    public void setDiffJson(String diffJson) { this.diffJson = diffJson; }
    public void setPolishStatus(String polishStatus) { this.polishStatus = polishStatus; }
}
