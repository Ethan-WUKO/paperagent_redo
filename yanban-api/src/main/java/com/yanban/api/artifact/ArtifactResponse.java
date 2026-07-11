package com.yanban.api.artifact;

import java.time.Instant;
import java.util.List;

public record ArtifactResponse(
        Long id,
        Long userId,
        Long sessionId,
        String title,
        String artifactType,
        String content,
        String sourceType,
        List<ArtifactSourceRef> sourceRefs,
        String status,
        String downloadUrl,
        String downloadFilename,
        String downloadContentType,
        Instant createdAt,
        Instant updatedAt
) {
}
