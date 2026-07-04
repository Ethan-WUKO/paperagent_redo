package com.yanban.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchKnowledgeSearchIndexClient implements KnowledgeSearchIndexClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeElasticsearchProperties properties;

    public ElasticsearchKnowledgeSearchIndexClient(RestClient restClient,
                                                   ObjectMapper objectMapper,
                                                   KnowledgeElasticsearchProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<KnowledgeSearchIndexHit> search(String query, Long userId, int topK, List<Double> queryVector) {
        try {
            Request request = new Request("POST", "/" + properties.getIndexName() + "/_search");
            request.setJsonEntity(buildQueryJson(query, userId, topK, queryVector));
            Response response = restClient.performRequest(request);
            return parseResponse(EntityUtils.toString(response.getEntity()));
        } catch (IOException ex) {
            throw new IllegalStateException("执行 Elasticsearch 检索失败", ex);
        }
    }

    private String buildQueryJson(String query, Long userId, int topK, List<Double> queryVector) throws IOException {
        String escapedQuery = objectMapper.writeValueAsString(query);
        String vectorJson = objectMapper.writeValueAsString(queryVector);
        return """
                {
                  \"size\": %d,
                  \"query\": {
                    \"script_score\": {
                      \"query\": {
                        \"bool\": {
                          \"filter\": [
                            {
                              \"bool\": {
                                \"should\": [
                                  { \"term\": { \"userId\": %d } },
                                  { \"term\": { \"isPublic\": true } }
                                ],
                                \"minimum_should_match\": 1
                              }
                            }
                          ],
                          \"should\": [
                            { \"match\": { \"text\": %s } }
                          ]
                        }
                      },
                      \"script\": {
                        \"source\": \"cosineSimilarity(params.queryVector, 'vector') + 1.0\",
                        \"params\": {
                          \"queryVector\": %s
                        }
                      }
                    }
                  }
                }
                """.formatted(topK, userId, escapedQuery, vectorJson);
    }

    private List<KnowledgeSearchIndexHit> parseResponse(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        List<KnowledgeSearchIndexHit> hits = new ArrayList<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            hits.add(new KnowledgeSearchIndexHit(
                    source.path("documentId").asLong(),
                    source.path("chunkIndex").asInt(),
                    source.path("text").asText(),
                    hit.path("_score").asDouble(0.0)
            ));
        }
        return hits;
    }
}
