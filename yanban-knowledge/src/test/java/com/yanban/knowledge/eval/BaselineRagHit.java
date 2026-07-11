package com.yanban.knowledge.eval;

public record BaselineRagHit(
        Long documentId,
        String filename,
        Integer chunkIndex,
        String chunkText,
        double score,
        String citationId,
        String source,
        String versionStatus,
        String visibility
) {
}
