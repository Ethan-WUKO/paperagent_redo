package com.yanban.api.artifact;

public record SaveArtifactToKnowledgeResponse(
        Long artifactId,
        Long documentId,
        String filename,
        String status
) {
}
