package com.yanban.knowledge.web;

public record ChunkUploadResponse(
        String uploadId,
        Integer chunkNumber,
        Integer totalChunks,
        String status,
        String tempObjectKey
) {
}
