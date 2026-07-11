package com.yanban.knowledge.service;

public record KnowledgeSearchResult(
        Long documentId,
        String filename,
        Integer chunkIndex,
        String chunkText,
        double score,
        boolean isPublic,
        String sourceType,
        String versionStatus,
        String lineageId,
        Integer versionNo,
        Long projectId,
        String canonicalKey,
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
                "USER_UPLOAD", "ACTIVE", null, 1, null, null,
                citationId(filename, chunkIndex), scoreBand(score), "knowledge_base", null, null);
    }

    public KnowledgeSearchResult(Long documentId,
                                 String filename,
                                 Integer chunkIndex,
                                 String chunkText,
                                 double score,
                                 boolean isPublic,
                                 String citationId,
                                 String scoreBand,
                                 String source,
                                 Double rerankScore,
                                 String rerankReason) {
        this(documentId, filename, chunkIndex, chunkText, score, isPublic,
                "USER_UPLOAD", "ACTIVE", null, 1, null, null,
                citationId, scoreBand, source, rerankScore, rerankReason);
    }

    public KnowledgeSearchResult(Long documentId,
                                 String filename,
                                 Integer chunkIndex,
                                 String chunkText,
                                 double score,
                                 boolean isPublic,
                                 String sourceType,
                                 String versionStatus,
                                 String lineageId,
                                 Integer versionNo,
                                 Long projectId,
                                 String canonicalKey) {
        this(documentId, filename, chunkIndex, chunkText, score, isPublic,
                sourceType == null || sourceType.isBlank() ? "USER_UPLOAD" : sourceType,
                versionStatus == null || versionStatus.isBlank() ? "ACTIVE" : versionStatus,
                lineageId,
                versionNo == null || versionNo < 1 ? 1 : versionNo,
                projectId,
                canonicalKey,
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
                sourceType,
                versionStatus,
                lineageId,
                versionNo,
                projectId,
                canonicalKey,
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
