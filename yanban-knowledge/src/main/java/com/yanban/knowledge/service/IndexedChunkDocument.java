package com.yanban.knowledge.service;

import java.util.List;

public record IndexedChunkDocument(
        Long chunkId,
        Long documentId,
        Long userId,
        Long projectId,
        boolean isPublic,
        String sourceType,
        String versionStatus,
        String lineageId,
        Integer versionNo,
        String canonicalKey,
        Integer chunkIndex,
        String text,
        List<Double> vector
) {
}
