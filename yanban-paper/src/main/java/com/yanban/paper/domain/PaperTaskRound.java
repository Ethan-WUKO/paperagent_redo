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
@Table(name = "paper_task_rounds")
public class PaperTaskRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "round_number", nullable = false)
    private Integer roundNumber;

    @Column(nullable = false, length = 64)
    private String stage;

    @Column(nullable = false, length = 32)
    private String status;

    @Lob
    @Column(name = "input_text")
    private String inputText;

    @Lob
    @Column(name = "output_text")
    private String outputText;

    @Column(name = "notes")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaperTaskRound() {
    }

    public PaperTaskRound(Long taskId, Integer roundNumber, String stage, String status,
                          String inputText, String outputText, String notes) {
        this.taskId = taskId;
        this.roundNumber = roundNumber;
        this.stage = stage;
        this.status = status;
        this.inputText = inputText;
        this.outputText = outputText;
        this.notes = notes;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public Integer getRoundNumber() { return roundNumber; }
    public String getStage() { return stage; }
    public String getStatus() { return status; }
    public String getInputText() { return inputText; }
    public String getOutputText() { return outputText; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setInputText(String inputText) { this.inputText = inputText; }
    public void setOutputText(String outputText) { this.outputText = outputText; }
    public void setNotes(String notes) { this.notes = notes; }
}
