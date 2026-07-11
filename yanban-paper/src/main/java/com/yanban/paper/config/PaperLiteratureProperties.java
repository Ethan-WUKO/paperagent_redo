package com.yanban.paper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.paper.literature")
public class PaperLiteratureProperties {

    private String indexName = "yanban-literature-cards-v1";
    private int maxAnalysisPerTask = 30;
    private int maxAnalysisPerRecommendation = 15;
    private int analysisConcurrency = 4;
    private String openAlexApiKey;
    private int openAlexMaxResultsPerQuery = 20;
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

    public int getMaxAnalysisPerRecommendation() {
        return maxAnalysisPerRecommendation;
    }

    public void setMaxAnalysisPerRecommendation(int maxAnalysisPerRecommendation) {
        this.maxAnalysisPerRecommendation = maxAnalysisPerRecommendation;
    }

    public int getAnalysisConcurrency() {
        return analysisConcurrency;
    }

    public void setAnalysisConcurrency(int analysisConcurrency) {
        this.analysisConcurrency = analysisConcurrency;
    }

    public String getOpenAlexApiKey() {
        return openAlexApiKey;
    }

    public void setOpenAlexApiKey(String openAlexApiKey) {
        this.openAlexApiKey = openAlexApiKey;
    }

    public int getOpenAlexMaxResultsPerQuery() {
        return openAlexMaxResultsPerQuery;
    }

    public void setOpenAlexMaxResultsPerQuery(int openAlexMaxResultsPerQuery) {
        this.openAlexMaxResultsPerQuery = openAlexMaxResultsPerQuery;
    }

    public boolean isIndexEnabled() {
        return indexEnabled;
    }

    public void setIndexEnabled(boolean indexEnabled) {
        this.indexEnabled = indexEnabled;
    }
}
