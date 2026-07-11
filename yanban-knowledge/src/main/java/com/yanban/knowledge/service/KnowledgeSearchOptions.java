package com.yanban.knowledge.service;

public record KnowledgeSearchOptions(
        Long userId,
        int topK,
        Long projectId,
        boolean includeSuperseded
) {
    public KnowledgeSearchOptions {
        topK = Math.max(0, topK);
    }

    public static KnowledgeSearchOptions activeOnly(Long userId, int topK) {
        return new KnowledgeSearchOptions(userId, topK, null, false);
    }
}
