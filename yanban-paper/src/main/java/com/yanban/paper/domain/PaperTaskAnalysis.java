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
@Table(name = "paper_task_analysis")
public class PaperTaskAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Lob
    @Column(name = "research_profile_json")
    private String researchProfileJson;

    @Lob
    @Column(name = "concept_ladder_json")
    private String conceptLadderJson;

    @Lob
    @Column(name = "gap_matrix_json")
    private String gapMatrixJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaperTaskAnalysis() {
    }

    public PaperTaskAnalysis(Long taskId) {
        this.taskId = taskId;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public String getResearchProfileJson() { return researchProfileJson; }
    public String getConceptLadderJson() { return conceptLadderJson; }
    public String getGapMatrixJson() { return gapMatrixJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setResearchProfileJson(String researchProfileJson) { this.researchProfileJson = researchProfileJson; }
    public void setConceptLadderJson(String conceptLadderJson) { this.conceptLadderJson = conceptLadderJson; }
    public void setGapMatrixJson(String gapMatrixJson) { this.gapMatrixJson = gapMatrixJson; }
}
