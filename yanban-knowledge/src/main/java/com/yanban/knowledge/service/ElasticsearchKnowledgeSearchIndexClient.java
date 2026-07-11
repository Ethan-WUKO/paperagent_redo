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
    public List<KnowledgeSearchIndexHit> search(String query, KnowledgeSearchOptions options, int topK, List<Double> queryVector) {
        try {
            Request request = new Request("POST", "/" + properties.getIndexName() + "/_search");
            request.setJsonEntity(buildQueryJson(query, options, topK, queryVector));
            Response response = restClient.performRequest(request);
            return parseResponse(EntityUtils.toString(response.getEntity()));
        } catch (IOException ex) {
            throw new IllegalStateException("执行 Elasticsearch 检索失败", ex);
        }
    }

    String buildQueryJson(String query, KnowledgeSearchOptions options, int topK, List<Double> queryVector) throws IOException {
        String escapedQuery = objectMapper.writeValueAsString(query);
        String vectorJson = objectMapper.writeValueAsString(queryVector);
        String versionStatuses = options.includeSuperseded()
                ? "[\"ACTIVE\", \"SUPERSEDED\"]"
                : "[\"ACTIVE\"]";
        String projectFilter = options.projectId() == null
                ? ""
                : """
                            ,
                            {
                              \"bool\": {
                                \"should\": [
                                  { \"bool\": { \"must_not\": { \"exists\": { \"field\": \"projectId\" } } } },
                                  { \"term\": { \"projectId\": %d } }
                                ],
                                \"minimum_should_match\": 1
                              }
                            }
                  """.formatted(options.projectId());
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
                            },
                            {
                              \"terms\": { \"versionStatus\": %s }
                            }
                            %s
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
                """.formatted(topK, options.userId(), versionStatuses, projectFilter, escapedQuery, vectorJson);
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
