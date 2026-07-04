package com.yanban.knowledge.service;

import java.util.List;

public record IndexedChunkDocument(
        Long chunkId,
        Long documentId,
        Long userId,
        boolean isPublic,
        Integer chunkIndex,
        String text,
        List<Double> vector
) {
}
