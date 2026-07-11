package com.yanban.knowledge.web;

import com.yanban.knowledge.domain.KbDocument;
import java.time.Instant;

public record KbDocumentResponse(
        Long id,
        Long userId,
        String filename,
        String status,
        boolean isPublic,
        String sourceType,
        Long projectId,
        String lineageId,
        Integer versionNo,
        String versionStatus,
        String sourceTaskType,
        Long sourceTaskId,
        Long sourceArtifactId,
        Long sourceDocumentId,
        String canonicalKey,
        Instant effectiveAt,
        Instant supersededAt,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static KbDocumentResponse from(KbDocument document) {
        return new KbDocumentResponse(
                document.getId(),
                document.getUserId(),
                document.getFilename(),
                document.getStatus(),
                Boolean.TRUE.equals(document.getIsPublic()),
                document.getSourceType(),
                document.getProjectId(),
                document.getLineageId(),
                document.getVersionNo(),
                document.getVersionStatus(),
                document.getSourceTaskType(),
                document.getSourceTaskId(),
                document.getSourceArtifactId(),
                document.getSourceDocumentId(),
                document.getCanonicalKey(),
                document.getEffectiveAt(),
                document.getSupersededAt(),
                document.getDeletedAt(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
