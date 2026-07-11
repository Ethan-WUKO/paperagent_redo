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
@Table(name = "agent_sessions")
public class AgentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 255)
    private String title;

    @Column(name = "model_provider_snapshot", nullable = false, length = 64)
    private String modelProviderSnapshot;

    @Column(name = "model_snapshot", nullable = false, length = 128)
    private String modelSnapshot;

    @Column(name = "max_steps", nullable = false)
    private Integer maxSteps;

    @Column(name = "rag_disabled", nullable = false)
    private Boolean ragDisabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentSession() {
    }

    public AgentSession(Long userId, String title, String modelProviderSnapshot, String modelSnapshot,
                        Integer maxSteps, Boolean ragDisabled) {
        this.userId = userId;
        this.title = title;
        this.modelProviderSnapshot = modelProviderSnapshot;
        this.modelSnapshot = modelSnapshot;
        this.maxSteps = maxSteps;
        this.ragDisabled = ragDisabled;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getModelProviderSnapshot() { return modelProviderSnapshot; }
    public String getModelSnapshot() { return modelSnapshot; }
    public Integer getMaxSteps() { return maxSteps; }
    public Boolean getRagDisabled() { return ragDisabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void updateTitle(String title) {
        this.title = title;
    }

    public void updateModel(String modelProviderSnapshot, String modelSnapshot) {
        this.modelProviderSnapshot = modelProviderSnapshot;
        this.modelSnapshot = modelSnapshot;
    }

    public void updateMaxSteps(Integer maxSteps) {
        this.maxSteps = maxSteps;
    }

    public void updateRagDisabled(Boolean ragDisabled) {
        this.ragDisabled = ragDisabled;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
