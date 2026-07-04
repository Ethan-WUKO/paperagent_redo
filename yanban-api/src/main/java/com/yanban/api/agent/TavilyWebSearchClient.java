package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class TavilyWebSearchClient implements WebSearchClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WebSearchProperties properties;

    public TavilyWebSearchClient(RestClient.Builder restClientBuilder,
                                 ObjectMapper objectMapper,
                                 WebSearchProperties properties) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return "tavily";
    }

    @Override
    public boolean available() {
        return StringUtils.hasText(properties.getTavily().getApiKey())
                && StringUtils.hasText(properties.getTavily().getEndpoint());
    }

    @Override
    public String unavailableReason() {
        if (!StringUtils.hasText(properties.getTavily().getApiKey())) {
            return "tavily_api_key_missing";
        }
        if (!StringUtils.hasText(properties.getTavily().getEndpoint())) {
            return "tavily_endpoint_missing";
        }
        return "";
    }

    @Override
    public WebSearchResponse search(String query, int limit) {
        try {
            JsonNode response = restClient.post()
                    .uri(properties.getTavily().getEndpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getTavily().getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(query, limit))
                    .retrieve()
                    .body(JsonNode.class);
            return parseResponse(query.trim(), limit, response);
        } catch (Exception ex) {
            return degraded(query, limit, abbreviate(errorMessage(ex), 500));
        }
    }

    private ObjectNode requestBody(String query, int limit) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("search_depth", properties.getTavily().getSearchDepth());
        body.put("auto_parameters", properties.getTavily().isAutoParameters());
        body.put("topic", properties.getTavily().getTopic());
        body.put("max_results", limit);
        body.put("include_answer", properties.getTavily().isIncludeAnswer());
        body.put("include_raw_content", properties.getTavily().isIncludeRawContent());
        body.put("include_images", properties.getTavily().isIncludeImages());
        body.put("include_usage", properties.getTavily().isIncludeUsage());
        return body;
    }

    private WebSearchResponse parseResponse(String query, int limit, JsonNode response) {
        if (response == null || response.isMissingNode() || response.isNull()) {
            return degraded(query, limit, "empty_tavily_response");
        }
        List<WebSearchItem> items = new ArrayList<>();
        JsonNode results = response.path("results");
        if (results.isArray()) {
            for (JsonNode result : results) {
                if (items.size() >= limit) {
                    break;
                }
                String title = text(result, "title");
                String url = text(result, "url");
                String snippet = firstText(result, "content", "snippet", "raw_content");
                if (!StringUtils.hasText(title) || !StringUtils.hasText(url)) {
                    continue;
                }
                items.add(new WebSearchItem(
                        title,
                        url,
                        snippet,
                        result.path("score").isNumber() ? result.path("score").asDouble() : null,
                        firstText(result, "published_date", "publishedAt", "date"),
                        "tavily"
                ));
            }
        }
        if (items.isEmpty()) {
            return new WebSearchResponse(provider(), query, true, "no_tavily_results", limit, List.of(),
                    intOrNull(response.path("usage").path("credits")),
                    text(response, "request_id"),
                    doubleOrNull(response.path("response_time")));
        }
        return new WebSearchResponse(provider(), query, false, "", limit, items,
                intOrNull(response.path("usage").path("credits")),
                text(response, "request_id"),
                doubleOrNull(response.path("response_time")));
    }

    private WebSearchResponse degraded(String query, int limit, String error) {
        return new WebSearchResponse(provider(), query, true, error, limit, List.of(), null, null, null);
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private Double doubleOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private String errorMessage(Exception ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
