package com.yanban.api.demo;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "yanban.demo")
public class DemoProperties {

    private boolean enabled = false;
    private boolean seedOnStartup = true;
    private String username = "demo";
    private String canonicalUrl = "http://yanban.online:18080/demo";
    private long maxUploadBytes = 3L * 1024 * 1024;
    private int maxUploadsPerReset = 5;
    private int maxChatMessagesPerHour = 60;
    private int maxPaperTasksPerReset = 2;
    private List<String> exampleQuestions = List.of(
            "根据知识库，概括这个项目能解决什么问题。",
            "演示文档里的组会时间、地点和下次 DDL 是什么？",
            "这个项目的 RAG 流程包含哪些步骤？",
            "用计划模式帮我把两周内完善 Agent 能力拆成任务。"
    );

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSeedOnStartup() {
        return seedOnStartup;
    }

    public void setSeedOnStartup(boolean seedOnStartup) {
        this.seedOnStartup = seedOnStartup;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    public int getMaxUploadsPerReset() {
        return maxUploadsPerReset;
    }

    public void setMaxUploadsPerReset(int maxUploadsPerReset) {
        this.maxUploadsPerReset = maxUploadsPerReset;
    }

    public int getMaxChatMessagesPerHour() {
        return maxChatMessagesPerHour;
    }

    public void setMaxChatMessagesPerHour(int maxChatMessagesPerHour) {
        this.maxChatMessagesPerHour = maxChatMessagesPerHour;
    }

    public int getMaxPaperTasksPerReset() {
        return maxPaperTasksPerReset;
    }

    public void setMaxPaperTasksPerReset(int maxPaperTasksPerReset) {
        this.maxPaperTasksPerReset = maxPaperTasksPerReset;
    }

    public List<String> getExampleQuestions() {
        return exampleQuestions;
    }

    public void setExampleQuestions(List<String> exampleQuestions) {
        if (exampleQuestions != null && !exampleQuestions.isEmpty()) {
            this.exampleQuestions = exampleQuestions;
        }
    }
}
