package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElasticsearchKnowledgeSearchIndexClientTest {

    @Test
    void queryFiltersActiveVersionsByDefault() throws Exception {
        ElasticsearchKnowledgeSearchIndexClient client = client();

        String json = client.buildQueryJson(
                "alpha",
                KnowledgeSearchOptions.activeOnly(1001L, 5),
                10,
                List.of(0.1d, 0.2d)
        );

        assertThat(json).contains("\"terms\": { \"versionStatus\": [\"ACTIVE\"] }");
        assertThat(json).contains("\"term\": { \"userId\": 1001 }");
        assertThat(json).doesNotContain("\"projectId\"");
    }

    @Test
    void queryCanIncludeSupersededAndProjectFilter() throws Exception {
        ElasticsearchKnowledgeSearchIndexClient client = client();

        String json = client.buildQueryJson(
                "alpha",
                new KnowledgeSearchOptions(1001L, 5, 42L, true),
                10,
                List.of(0.1d, 0.2d)
        );

        assertThat(json).contains("\"terms\": { \"versionStatus\": [\"ACTIVE\", \"SUPERSEDED\"] }");
        assertThat(json).contains("\"exists\": { \"field\": \"projectId\" }");
        assertThat(json).contains("\"term\": { \"projectId\": 42 }");
    }

    private ElasticsearchKnowledgeSearchIndexClient client() {
        KnowledgeElasticsearchProperties properties = new KnowledgeElasticsearchProperties();
        properties.setIndexName("yanban-kb-chunks-v1");
        return new ElasticsearchKnowledgeSearchIndexClient(null, new ObjectMapper(), properties);
    }
}
