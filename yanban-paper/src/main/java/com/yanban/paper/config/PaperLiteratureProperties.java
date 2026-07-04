package com.yanban.paper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.paper.literature")
public class PaperLiteratureProperties {

    private String indexName = "yanban-literature-cards-v1";
    private int maxAnalysisPerTask = 30;
    private boolean indexEnabled = true;

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public int getMaxAnalysisPerTask() {
        return maxAnalysisPerTask;
    }

    public void setMaxAnalysisPerTask(int maxAnalysisPerTask) {
        this.maxAnalysisPerTask = maxAnalysisPerTask;
    }

    public boolean isIndexEnabled() {
        return indexEnabled;
    }

    public void setIndexEnabled(boolean indexEnabled) {
        this.indexEnabled = indexEnabled;
    }
}
