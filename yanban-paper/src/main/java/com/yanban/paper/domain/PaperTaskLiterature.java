package com.yanban.paper.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "paper_task_literature")
public class PaperTaskLiterature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "relevance_score")
    private Double relevanceScore;

    @Column(name = "narrative_role", length = 32)
    private String narrativeRole;

    @Column(name = "ladder_node", length = 255)
    private String ladderNode;

    @Column(nullable = false)
    private Boolean selected = false;

    @Column(name = "source_query")
    private String sourceQuery;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaperTaskLiterature() {
    }

    public PaperTaskLiterature(Long taskId, Long cardId) {
        this.taskId = taskId;
        this.cardId = cardId;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public Long getCardId() { return cardId; }
    public Double getRelevanceScore() { return relevanceScore; }
    public String getNarrativeRole() { return narrativeRole; }
    public String getLadderNode() { return ladderNode; }
    public Boolean getSelected() { return selected; }
    public String getSourceQuery() { return sourceQuery; }
    public Instant getCreatedAt() { return createdAt; }

    public void setRelevanceScore(Double relevanceScore) { this.relevanceScore = relevanceScore; }
    public void setNarrativeRole(String narrativeRole) { this.narrativeRole = narrativeRole; }
    public void setLadderNode(String ladderNode) { this.ladderNode = ladderNode; }
    public void setSelected(Boolean selected) { this.selected = selected; }
    public void setSourceQuery(String sourceQuery) { this.sourceQuery = sourceQuery; }
}
