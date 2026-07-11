package com.yanban.core.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "agent_tool_runs")
public class AgentToolRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    @Lob
    @Column(name = "input_json", columnDefinition = "LONGTEXT")
    private String inputJson;

    @Lob
    @Column(name = "output_json", columnDefinition = "LONGTEXT")
    private String outputJson;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentToolRun() {
    }

    public AgentToolRun(Long sessionId, Long messageId, String toolName, String inputJson,
                        String outputJson, String status, Long durationMs, String errorMessage) {
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.toolName = toolName;
        this.inputJson = inputJson;
        this.outputJson = outputJson;
        this.status = status;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getMessageId() { return messageId; }
    public String getToolName() { return toolName; }
    public String getInputJson() { return inputJson; }
    public String getOutputJson() { return outputJson; }
    public String getStatus() { return status; }
    public Long getDurationMs() { return durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
