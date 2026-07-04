package com.yanban.knowledge.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.service.DashScopeEmbeddingClient;
import com.yanban.knowledge.service.EmbeddingClient;
import com.yanban.knowledge.service.HttpOcrProvider;
import com.yanban.knowledge.service.OcrProvider;
import io.minio.MinioClient;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        KnowledgeStorageProperties.class,
        KnowledgeUploadProperties.class,
        KnowledgeElasticsearchProperties.class,
        KnowledgeEmbeddingProperties.class,
        KnowledgeOcrProperties.class
})
public class KnowledgeStorageConfig {

    @Bean
    public MinioClient minioClient(KnowledgeStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(KnowledgeElasticsearchProperties properties) {
        return RestClient.builder(HttpHost.create(properties.getEndpoint())).build();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }

    @Bean
    public EmbeddingClient embeddingClient(KnowledgeEmbeddingProperties properties) {
        return new DashScopeEmbeddingClient(
                org.springframework.web.client.RestClient.builder().baseUrl(properties.getApiUrl()).build(),
                properties
        );
    }

    @Bean
    public OcrProvider ocrProvider(KnowledgeOcrProperties properties, ObjectMapper objectMapper) {
        org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder();
        if (properties.getApiUrl() != null && !properties.getApiUrl().isBlank()) {
            builder = builder.baseUrl(properties.getApiUrl());
        }
        return new HttpOcrProvider(builder.build(), properties, objectMapper);
    }
}
