package com.yanban.knowledge.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.knowledge.embedding")
public class KnowledgeEmbeddingProperties {

    private String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
    private String apiKey;
    private String model = "text-embedding-v4";
    private Duration timeout = Duration.ofSeconds(30);

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
