package com.yanban.api.agent;

public record AgentRetrievedChunkDebug(
        String source,
        Long documentId,
        String filename,
        Integer chunkIndex,
        String citationId,
        Double score,
        String content
) {
}
