package com.yanban.knowledge.service;

public record KnowledgeSearchResult(
        Long documentId,
        String filename,
        Integer chunkIndex,
        String chunkText,
        double score,
        boolean isPublic,
        String citationId,
        String scoreBand,
        String source,
        Double rerankScore,
        String rerankReason
) {
    public KnowledgeSearchResult(Long documentId,
                                 String filename,
                                 Integer chunkIndex,
                                 String chunkText,
                                 double score,
                                 boolean isPublic) {
        this(documentId, filename, chunkIndex, chunkText, score, isPublic,
                citationId(filename, chunkIndex), scoreBand(score), "knowledge_base", null, null);
    }

    public KnowledgeSearchResult withRerank(double rerankScore, String rerankReason) {
        return new KnowledgeSearchResult(
                documentId,
                filename,
                chunkIndex,
                chunkText,
                score,
                isPublic,
                citationId,
                scoreBand(rerankScore),
                source,
                rerankScore,
                rerankReason
        );
    }

    private static String citationId(String filename, Integer chunkIndex) {
        String safeName = filename == null ? "document" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return safeName + "#chunk-" + (chunkIndex == null ? 0 : chunkIndex);
    }

    private static String scoreBand(double score) {
        if (score >= 1.5d) {
            return "high";
        }
        if (score >= 0.8d) {
            return "medium";
        }
        return "low";
    }
}
