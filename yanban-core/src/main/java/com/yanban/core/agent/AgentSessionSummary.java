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
@Table(name = "agent_session_summaries")
public class AgentSessionSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Lob
    @Column(name = "summary_text", nullable = false, columnDefinition = "LONGTEXT")
    private String summaryText;

    @Column(name = "covered_message_id")
    private Long coveredMessageId;

    @Column(name = "message_count", nullable = false)
    private Integer messageCount;

    @Column(name = "model_provider_snapshot", length = 64)
    private String modelProviderSnapshot;

    @Column(name = "model_snapshot", length = 128)
    private String modelSnapshot;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentSessionSummary() {
    }

    public AgentSessionSummary(Long sessionId,
                               Long userId,
                               String summaryText,
                               Long coveredMessageId,
                               Integer messageCount,
                               String modelProviderSnapshot,
                               String modelSnapshot) {
        this.sessionId = sessionId;
        this.userId = userId;
        update(summaryText, coveredMessageId, messageCount, modelProviderSnapshot, modelSnapshot);
    }

    public void update(String summaryText,
                       Long coveredMessageId,
                       Integer messageCount,
                       String modelProviderSnapshot,
                       String modelSnapshot) {
        if (summaryText == null || summaryText.isBlank()) {
            throw new IllegalArgumentException("summaryText must not be blank");
        }
        this.summaryText = summaryText.trim();
        this.coveredMessageId = coveredMessageId;
        this.messageCount = messageCount == null || messageCount < 0 ? 0 : messageCount;
        this.modelProviderSnapshot = blankToNull(modelProviderSnapshot);
        this.modelSnapshot = blankToNull(modelSnapshot);
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public String getSummaryText() { return summaryText; }
    public Long getCoveredMessageId() { return coveredMessageId; }
    public Integer getMessageCount() { return messageCount; }
    public String getModelProviderSnapshot() { return modelProviderSnapshot; }
    public String getModelSnapshot() { return modelSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
