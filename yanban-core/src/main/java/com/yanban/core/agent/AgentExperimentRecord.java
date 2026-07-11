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
@Table(name = "agent_experiment_records")
public class AgentExperimentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "client_request_id", length = 128)
    private String clientRequestId;

    @Column(name = "runtime_mode", nullable = false, length = 64)
    private String runtimeMode;

    @Column(name = "rag_mode", nullable = false, length = 64)
    private String ragMode;

    @Column(name = "memory_mode", nullable = false, length = 64)
    private String memoryMode;

    @Column(name = "tool_calling_mode", nullable = false, length = 64)
    private String toolCallingMode;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "retrieved_chunk_count")
    private Integer retrievedChunkCount;

    @Column(name = "memory_window_size")
    private Integer memoryWindowSize;

    @Column(name = "eval_record_version", nullable = false)
    private Integer evalRecordVersion;

    @Lob
    @Column(name = "debug_flags_json", columnDefinition = "LONGTEXT")
    private String debugFlagsJson;

    @Lob
    @Column(name = "tool_trace_json", columnDefinition = "LONGTEXT")
    private String toolTraceJson;

    @Lob
    @Column(name = "memory_window_json", columnDefinition = "LONGTEXT")
    private String memoryWindowJson;

    @Lob
    @Column(name = "retrieved_chunks_json", columnDefinition = "LONGTEXT")
    private String retrievedChunksJson;

    @Lob
    @Column(name = "final_citations_json", columnDefinition = "LONGTEXT")
    private String finalCitationsJson;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "fallback_reason", length = 500)
    private String fallbackReason;

    @Column(name = "answer_preview", length = 4000)
    private String answerPreview;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentExperimentRecord() {
    }

    public AgentExperimentRecord(Long sessionId,
                                 Long userId,
                                 String clientRequestId,
                                 String runtimeMode,
                                 String ragMode,
                                 String memoryMode,
                                 String toolCallingMode,
                                 boolean success,
                                 Long latencyMs,
                                 Integer retrievedChunkCount,
                                 Integer memoryWindowSize,
                                 Integer evalRecordVersion,
                                 String debugFlagsJson,
                                 String toolTraceJson,
                                 String memoryWindowJson,
                                 String retrievedChunksJson,
                                 String finalCitationsJson,
                                 String errorMessage,
                                 String fallbackReason,
                                 String answerPreview) {
        this.sessionId = requireNonNull(sessionId, "sessionId");
        this.userId = requireNonNull(userId, "userId");
        this.clientRequestId = trimToNull(clientRequestId);
        this.runtimeMode = requireText(runtimeMode, "runtimeMode");
        this.ragMode = requireText(ragMode, "ragMode");
        this.memoryMode = requireText(memoryMode, "memoryMode");
        this.toolCallingMode = requireText(toolCallingMode, "toolCallingMode");
        this.success = success;
        this.latencyMs = latencyMs;
        this.retrievedChunkCount = nonNegative(retrievedChunkCount);
        this.memoryWindowSize = nonNegative(memoryWindowSize);
        this.evalRecordVersion = evalRecordVersion == null || evalRecordVersion < 1 ? 1 : evalRecordVersion;
        this.debugFlagsJson = trimToNull(debugFlagsJson);
        this.toolTraceJson = trimToNull(toolTraceJson);
        this.memoryWindowJson = trimToNull(memoryWindowJson);
        this.retrievedChunksJson = trimToNull(retrievedChunksJson);
        this.finalCitationsJson = trimToNull(finalCitationsJson);
        this.errorMessage = trimToNull(limit(errorMessage, 1000));
        this.fallbackReason = trimToNull(limit(fallbackReason, 500));
        this.answerPreview = trimToNull(limit(answerPreview, 4000));
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public String getRuntimeMode() {
        return runtimeMode;
    }

    public String getRagMode() {
        return ragMode;
    }

    public String getMemoryMode() {
        return memoryMode;
    }

    public String getToolCallingMode() {
        return toolCallingMode;
    }

    public boolean isSuccess() {
        return success;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Integer getRetrievedChunkCount() {
        return retrievedChunkCount;
    }

    public Integer getMemoryWindowSize() {
        return memoryWindowSize;
    }

    public Integer getEvalRecordVersion() {
        return evalRecordVersion;
    }

    public String getDebugFlagsJson() {
        return debugFlagsJson;
    }

    public String getToolTraceJson() {
        return toolTraceJson;
    }

    public String getMemoryWindowJson() {
        return memoryWindowJson;
    }

    public String getRetrievedChunksJson() {
        return retrievedChunksJson;
    }

    public String getFinalCitationsJson() {
        return finalCitationsJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public String getAnswerPreview() {
        return answerPreview;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private Long requireNonNull(Long value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private String requireText(String value, String name) {
        String text = trimToNull(value);
        if (text == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
