package com.yanban.paper.service;

public interface PaperModelClient {
    String complete(String systemPrompt, String userPrompt, Double temperature, Integer maxTokens);
}
