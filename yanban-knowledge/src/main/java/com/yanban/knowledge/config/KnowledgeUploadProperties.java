package com.yanban.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.knowledge.upload")
public class KnowledgeUploadProperties {

    private String tempPrefix = "kb/temp";
    private String objectPrefix = "kb/documents";
    private String processingTopic = "file-processing";

    public String getTempPrefix() {
        return tempPrefix;
    }

    public void setTempPrefix(String tempPrefix) {
        this.tempPrefix = tempPrefix;
    }

    public String getObjectPrefix() {
        return objectPrefix;
    }

    public void setObjectPrefix(String objectPrefix) {
        this.objectPrefix = objectPrefix;
    }

    public String getProcessingTopic() {
        return processingTopic;
    }

    public void setProcessingTopic(String processingTopic) {
        this.processingTopic = processingTopic;
    }
}
