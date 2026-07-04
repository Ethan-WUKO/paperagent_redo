package com.yanban.knowledge.service;

public record FileProcessingMessage(
        Long documentId,
        Long userId,
        String objectKey
) {
}
