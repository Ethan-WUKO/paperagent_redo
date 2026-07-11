package com.yanban.core.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.model.openrouter")
public class OpenRouterProperties {

    private String apiUrl = "https://openrouter.ai/api/v1/chat/completions";
    private String apiKey;
    private String hy3FreeModel = "tencent/hy3:free";
    private String hy3Model = "tencent/hy3";

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

    public String getHy3FreeModel() {
        return hy3FreeModel;
    }

    public void setHy3FreeModel(String hy3FreeModel) {
        this.hy3FreeModel = hy3FreeModel;
    }

    public String getHy3Model() {
        return hy3Model;
    }

    public void setHy3Model(String hy3Model) {
        this.hy3Model = hy3Model;
    }
}
