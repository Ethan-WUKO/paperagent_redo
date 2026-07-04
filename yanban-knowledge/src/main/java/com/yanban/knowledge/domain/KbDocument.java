package com.yanban.knowledge.domain;

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
@Table(name = "kb_documents")
public class KbDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType = "USER_UPLOAD";

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "mime_type", length = 255)
    private String mimeType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "error_message")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KbDocument() {
    }

    public KbDocument(Long userId, String filename, String status, Boolean isPublic) {
        this.userId = userId;
        this.filename = filename;
        this.status = status;
        this.isPublic = isPublic;
        this.sourceType = "USER_UPLOAD";
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getFilename() { return filename; }
    public String getStatus() { return status; }
    public Boolean getIsPublic() { return isPublic; }
    public String getSourceType() { return sourceType; }
    public String getObjectKey() { return objectKey; }
    public String getMimeType() { return mimeType; }
    public Long getFileSize() { return fileSize; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType == null || sourceType.isBlank() ? "USER_UPLOAD" : sourceType; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
