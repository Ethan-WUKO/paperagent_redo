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
@Table(name = "paper_task_clarifications")
public class PaperTaskClarification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(name = "question_json", nullable = false)
    private String questionJson;

    @Column(name = "options_json")
    private String optionsJson;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "user_answer_json")
    private String userAnswerJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    protected PaperTaskClarification() {
    }

    public PaperTaskClarification(Long taskId, String type, String questionJson, String optionsJson, String status) {
        this.taskId = taskId;
        this.type = type;
        this.questionJson = questionJson;
        this.optionsJson = optionsJson;
        this.status = status;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public String getType() { return type; }
    public String getQuestionJson() { return questionJson; }
    public String getOptionsJson() { return optionsJson; }
    public String getStatus() { return status; }
    public String getUserAnswerJson() { return userAnswerJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAnsweredAt() { return answeredAt; }

    public void answer(String userAnswerJson) {
        this.userAnswerJson = userAnswerJson;
        this.status = "ANSWERED";
        this.answeredAt = Instant.now();
    }
}
