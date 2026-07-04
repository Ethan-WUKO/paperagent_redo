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
@Table(name = "agent_task_events")
public class AgentTaskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_type", nullable = false, length = 64)
    private String taskType;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(length = 64)
    private String stage;

    @Column(length = 32)
    private String status;

    @Column(length = 500)
    private String message;

    @Lob
    @Column(name = "payload_json")
    private String payloadJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentTaskEvent() {
    }

    public AgentTaskEvent(String taskType,
                          Long taskId,
                          Long userId,
                          String eventType,
                          String stage,
                          String status,
                          String message,
                          String payloadJson) {
        this.taskType = taskType;
        this.taskId = taskId;
        this.userId = userId;
        this.eventType = eventType;
        this.stage = stage;
        this.status = status;
        this.message = message;
        this.payloadJson = payloadJson;
    }

    public Long getId() { return id; }
    public String getTaskType() { return taskType; }
    public Long getTaskId() { return taskId; }
    public Long getUserId() { return userId; }
    public String getEventType() { return eventType; }
    public String getStage() { return stage; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
}
