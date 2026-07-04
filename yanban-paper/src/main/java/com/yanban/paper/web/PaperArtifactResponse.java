package com.yanban.paper.web;

import com.yanban.paper.domain.PaperTaskArtifact;
import java.time.Instant;

public record PaperArtifactResponse(
        Long id,
        Long taskId,
        String type,
        String objectKey,
        Integer version,
        String metadataJson,
        Instant createdAt
) {
    public static PaperArtifactResponse from(PaperTaskArtifact artifact) {
        return new PaperArtifactResponse(
                artifact.getId(),
                artifact.getTaskId(),
                artifact.getType(),
                artifact.getObjectKey(),
                artifact.getVersion(),
                artifact.getMetadataJson(),
                artifact.getCreatedAt()
        );
    }
}
