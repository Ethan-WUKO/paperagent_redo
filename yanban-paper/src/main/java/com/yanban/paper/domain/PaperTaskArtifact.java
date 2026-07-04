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
@Table(name = "paper_task_artifacts")
public class PaperTaskArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "metadata_json", length = 10000)
    private String metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaperTaskArtifact() {
    }

    public PaperTaskArtifact(Long taskId, String type, String objectKey, Integer version) {
        this.taskId = taskId;
        this.type = type;
        this.objectKey = objectKey;
        this.version = version;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public String getType() { return type; }
    public String getObjectKey() { return objectKey; }
    public Integer getVersion() { return version; }
    public String getMetadataJson() { return metadataJson; }
    public Instant getCreatedAt() { return createdAt; }

    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
}
