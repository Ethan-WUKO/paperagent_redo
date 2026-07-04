package com.yanban.paper.literature;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.config.PaperLiteratureProperties;
import com.yanban.paper.domain.LiteratureCard;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class LiteratureCardIndexService {

    private static final Logger log = LoggerFactory.getLogger(LiteratureCardIndexService.class);

    private final ObjectProvider<ElasticsearchClient> elasticsearchClientProvider;
    private final PaperLiteratureProperties properties;
    private final ObjectMapper objectMapper;

    public LiteratureCardIndexService(ObjectProvider<ElasticsearchClient> elasticsearchClientProvider,
                                      PaperLiteratureProperties properties,
                                      ObjectMapper objectMapper) {
        this.elasticsearchClientProvider = elasticsearchClientProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void index(LiteratureCard card) {
        if (!properties.isIndexEnabled() || card == null || card.getId() == null) return;
        ElasticsearchClient client = elasticsearchClientProvider.getIfAvailable();
        if (client == null) return;
        try {
            ensureIndex(client);
            client.index(IndexRequest.of(builder -> builder
                    .index(indexName())
                    .id(String.valueOf(card.getId()))
                    .document(document(card))));
        } catch (Exception ex) {
            log.debug("Skip indexing literature card {} due to Elasticsearch error: {}", card.getId(), ex.getMessage());
        }
    }

    private void ensureIndex(ElasticsearchClient client) throws Exception {
        String indexName = indexName();
        boolean exists = client.indices().exists(request -> request.index(indexName)).value();
        if (exists) return;
        client.indices().create(request -> request
                .index(indexName)
                .mappings(mapping -> mapping
                        .properties("title", Property.of(p -> p.text(t -> t)))
                        .properties("abstractText", Property.of(p -> p.text(t -> t)))
                        .properties("analysisText", Property.of(p -> p.text(t -> t)))
                        .properties("domainTerms", Property.of(p -> p.keyword(k -> k)))
                        .properties("doi", Property.of(p -> p.keyword(k -> k)))
                        .properties("openAlexId", Property.of(p -> p.keyword(k -> k)))
                        .properties("arxivId", Property.of(p -> p.keyword(k -> k)))
                        .properties("year", Property.of(p -> p.integer(i -> i)))
                ));
    }

    private Map<String, Object> document(LiteratureCard card) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", card.getId());
        doc.put("title", card.getTitle());
        doc.put("authors", parseList(card.getAuthors()));
        doc.put("year", card.getPublicationYear());
        doc.put("venue", card.getVenue());
        doc.put("doi", card.getDoi());
        doc.put("arxivId", card.getArxivId());
        doc.put("openAlexId", card.getOpenAlexId());
        doc.put("url", card.getUrl());
        doc.put("abstractText", card.getAbstractText());
        doc.put("fieldsOfStudy", parseList(card.getFieldsOfStudyJson()));
        Map<String, Object> analysis = parseMap(card.getAnalysisJson());
        doc.put("analysis", analysis);
        doc.put("analysisText", analysisText(analysis));
        doc.put("domainTerms", analysis.getOrDefault("domainTerms", List.of()));
        doc.put("analysisModelVersion", card.getAnalysisModelVersion());
        doc.put("indexedAt", Instant.now().toString());
        return doc;
    }

    private String analysisText(Map<String, Object> analysis) {
        if (analysis == null || analysis.isEmpty()) return "";
        return String.join("\n", analysis.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .toList());
    }

    private List<?> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<?>>() {});
        } catch (Exception ex) {
            return List.of(json);
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("raw", json);
        }
    }

    private String indexName() {
        String value = properties.getIndexName();
        return value == null || value.isBlank() ? "yanban-literature-cards-v1" : value;
    }
}
