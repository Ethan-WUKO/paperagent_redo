package com.yanban.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeOcrProperties;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

public class HttpOcrProvider implements OcrProvider {

    private final RestClient restClient;
    private final KnowledgeOcrProperties properties;
    private final ObjectMapper objectMapper;

    public HttpOcrProvider(RestClient restClient, KnowledgeOcrProperties properties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String extractText(byte[] imageBytes, String mimeType, String filename) {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getApiUrl())) {
            throw new IllegalStateException("OCR 未配置");
        }
        try {
            String response = restClient.post()
                    .uri(properties.getApiUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, StringUtils.hasText(properties.getApiKey()) ? "Bearer " + properties.getApiKey() : null)
                    .body(objectMapper.createObjectNode()
                            .put("filename", filename == null ? "image" : filename)
                            .put("mimeType", mimeType == null ? "application/octet-stream" : mimeType)
                            .put("contentBase64", Base64.getEncoder().encodeToString(imageBytes)))
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            String text = root.path("text").asText(null);
            if (!StringUtils.hasText(text)) {
                throw new IllegalStateException("OCR 返回空文本");
            }
            return text;
        } catch (Exception ex) {
            throw new IllegalStateException("OCR 调用失败", ex);
        }
    }
}
