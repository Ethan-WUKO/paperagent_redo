package com.yanban.core.rag;

public record KnowledgeSnippet(
        Long documentId,
        String filename,
        Integer chunkIndex,
        String content,
        double score,
        String citationId,
        String scoreBand,
        String source,
        Double rerankScore,
        String rerankReason
) {
    public KnowledgeSnippet(Long documentId,
                            String filename,
                            Integer chunkIndex,
                            String content,
                            double score) {
        this(documentId, filename, chunkIndex, content, score, null, null, null, null, null);
    }

    public KnowledgeSnippet(Long documentId,
                            String filename,
                            Integer chunkIndex,
                            String content,
                            double score,
                            String citationId,
                            String scoreBand,
                            String source) {
        this(documentId, filename, chunkIndex, content, score, citationId, scoreBand, source, null, null);
    }
}
