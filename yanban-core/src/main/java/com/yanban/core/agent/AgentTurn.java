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
@Table(name = "agent_turns")
public class AgentTurn {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_message_id")
    private Long userMessageId;

    @Column(name = "assistant_message_id")
    private Long assistantMessageId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentTurn() {
    }

    public AgentTurn(Long sessionId, Long userId, Long userMessageId) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.userMessageId = userMessageId;
        this.status = STATUS_RUNNING;
    }

    public void complete(Long assistantMessageId) {
        this.assistantMessageId = assistantMessageId;
        this.status = STATUS_COMPLETED;
        this.errorMessage = null;
        this.finishedAt = Instant.now();
    }

    public void fail(Long assistantMessageId, String errorMessage) {
        this.assistantMessageId = assistantMessageId;
        this.status = STATUS_FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = Instant.now();
    }

    public void cancel(String errorMessage) {
        this.status = STATUS_CANCELLED;
        this.errorMessage = errorMessage;
        this.finishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public Long getUserMessageId() { return userMessageId; }
    public Long getAssistantMessageId() { return assistantMessageId; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
