package com.yanban.knowledge.web;

import com.yanban.knowledge.domain.KbDocument;

public record KbDocumentPreviewResponse(
        Long id,
        String filename,
        String status,
        String mimeType,
        Long fileSize,
        int totalChunks,
        int previewChunks,
        int maxChars,
        boolean truncated,
        String content
) {
    public static KbDocumentPreviewResponse of(KbDocument document,
                                               int totalChunks,
                                               int previewChunks,
                                               int maxChars,
                                               boolean truncated,
                                               String content) {
        return new KbDocumentPreviewResponse(
                document.getId(),
                document.getFilename(),
                document.getStatus(),
                document.getMimeType(),
                document.getFileSize(),
                totalChunks,
                previewChunks,
                maxChars,
                truncated,
                content
        );
    }
}
