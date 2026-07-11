package com.yanban.api.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "yanban.agent.web-search")
public class WebSearchProperties {

    private String provider = "duckduckgo";
    private String endpoint = "https://duckduckgo.com/html/";
    private Duration timeout = Duration.ofSeconds(12);
    private int maxResults = 8;
    private Duration cacheTtl = Duration.ofMinutes(30);
    private boolean fallbackToDuckDuckGo = true;
    private Tavily tavily = new Tavily();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public boolean isFallbackToDuckDuckGo() {
        return fallbackToDuckDuckGo;
    }

    public void setFallbackToDuckDuckGo(boolean fallbackToDuckDuckGo) {
        this.fallbackToDuckDuckGo = fallbackToDuckDuckGo;
    }

    public Tavily getTavily() {
        return tavily;
    }

    public void setTavily(Tavily tavily) {
        this.tavily = tavily;
    }

    public static class Tavily {

        private String endpoint = "https://api.tavily.com/search";
        private String apiKey = "";
        private String searchDepth = "basic";
        private boolean autoParameters = false;
        private String topic = "general";
        private boolean includeAnswer = false;
        private boolean includeRawContent = false;
        private boolean includeImages = false;
        private boolean includeUsage = true;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getSearchDepth() {
            return searchDepth;
        }

        public void setSearchDepth(String searchDepth) {
            this.searchDepth = searchDepth;
        }

        public boolean isAutoParameters() {
            return autoParameters;
        }

        public void setAutoParameters(boolean autoParameters) {
            this.autoParameters = autoParameters;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public boolean isIncludeAnswer() {
            return includeAnswer;
        }

        public void setIncludeAnswer(boolean includeAnswer) {
            this.includeAnswer = includeAnswer;
        }

        public boolean isIncludeRawContent() {
            return includeRawContent;
        }

        public void setIncludeRawContent(boolean includeRawContent) {
            this.includeRawContent = includeRawContent;
        }

        public boolean isIncludeImages() {
            return includeImages;
        }

        public void setIncludeImages(boolean includeImages) {
            this.includeImages = includeImages;
        }

        public boolean isIncludeUsage() {
            return includeUsage;
        }

        public void setIncludeUsage(boolean includeUsage) {
            this.includeUsage = includeUsage;
        }
    }
}
