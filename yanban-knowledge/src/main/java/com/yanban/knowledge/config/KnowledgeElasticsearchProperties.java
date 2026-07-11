package com.yanban.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.knowledge.elasticsearch")
public class KnowledgeElasticsearchProperties {

    private String endpoint;
    private String indexName = "yanban-kb-chunks-v1";
    private Integer vectorDimensions = 1024;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public Integer getVectorDimensions() {
        return vectorDimensions;
    }

    public void setVectorDimensions(Integer vectorDimensions) {
        this.vectorDimensions = vectorDimensions;
    }
}
