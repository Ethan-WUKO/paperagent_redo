package com.yanban.knowledge.service;

public interface OcrProvider {
    String extractText(byte[] imageBytes, String mimeType, String filename);
}
