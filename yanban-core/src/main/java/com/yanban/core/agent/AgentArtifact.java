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
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "agent_artifacts")
public class AgentArtifact {

    public static final String TYPE_MARKDOWN = "MARKDOWN";
    public static final String TYPE_TEXT = "TEXT";
    public static final String SOURCE_AGENT_TOOL = "AGENT_TOOL";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "artifact_type", nullable = false, length = 64)
    private String artifactType;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType;

    @Lob
    @Column(name = "source_refs_json", columnDefinition = "LONGTEXT")
    private String sourceRefsJson;

    @Column(nullable = false, length = 32)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentArtifact() {
    }

    public AgentArtifact(Long userId,
                         Long sessionId,
                         String title,
                         String artifactType,
                         String content,
                         String sourceType,
                         String sourceRefsJson) {
        this.userId = requireNonNull(userId, "userId");
        this.sessionId = sessionId;
        this.title = requireText(title, "title");
        this.artifactType = normalize(artifactType, TYPE_MARKDOWN);
        this.content = requireContent(content);
        this.sourceType = normalize(sourceType, SOURCE_AGENT_TOOL);
        this.sourceRefsJson = blankToNull(sourceRefsJson);
        this.status = STATUS_ACTIVE;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getSessionId() { return sessionId; }
    public String getTitle() { return title; }
    public String getArtifactType() { return artifactType; }
    public String getContent() { return content; }
    public String getSourceType() { return sourceType; }
    public String getSourceRefsJson() { return sourceRefsJson; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void markDeleted() {
        this.status = STATUS_DELETED;
    }

    private Long requireNonNull(Long value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        return value;
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
