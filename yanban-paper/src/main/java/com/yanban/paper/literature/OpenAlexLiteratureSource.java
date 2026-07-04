package com.yanban.paper.literature;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.client.RestClient;

public class OpenAlexLiteratureSource implements LiteratureSource {

    private final RestClient restClient;

    public OpenAlexLiteratureSource() {
        this(RestClient.builder().baseUrl("https://api.openalex.org").build());
    }

    public OpenAlexLiteratureSource(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public String name() {
        return "openalex";
    }

    @Override
    public List<LiteratureCandidate> search(String query, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode root = restClient.get()
                .uri("/works?search=" + encoded + "&per-page=" + Math.max(1, Math.min(limit, 50)))
                .retrieve()
                .body(JsonNode.class);
        List<LiteratureCandidate> candidates = new ArrayList<>();
        JsonNode results = root == null ? null : root.get("results");
        if (results == null || !results.isArray()) {
            return candidates;
        }
        for (JsonNode item : results) {
            candidates.add(new LiteratureCandidate(
                    name(),
                    text(item, "doi"),
                    null,
                    text(item, "id"),
                    null,
                    text(item, "title"),
                    authors(item.path("authorships")),
                    intOrNull(item, "publication_year"),
                    item.path("primary_location").path("source").path("display_name").asText(null),
                    abstractFromInvertedIndex(item.path("abstract_inverted_index")),
                    text(item, "id"),
                    item.path("primary_location").path("pdf_url").asText(null),
                    intOrNull(item, "cited_by_count"),
                    strings(item.path("referenced_works")),
                    strings(item.path("concepts"), "display_name"),
                    query
            ));
        }
        return candidates;
    }

    private String abstractFromInvertedIndex(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        List<Token> tokens = new ArrayList<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isArray()) {
                entry.getValue().forEach(index -> tokens.add(new Token(index.asInt(), entry.getKey())));
            }
        });
        tokens.sort(Comparator.comparingInt(Token::index));
        StringBuilder builder = new StringBuilder();
        for (Token token : tokens) {
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(token.word());
        }
        return builder.toString();
    }

    private List<String> authors(JsonNode authorships) {
        List<String> authors = new ArrayList<>();
        if (authorships != null && authorships.isArray()) {
            for (JsonNode node : authorships) {
                String name = node.path("author").path("display_name").asText(null);
                if (name != null && !name.isBlank()) authors.add(name);
            }
        }
        return authors;
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private List<String> strings(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> {
                String value = item.path(field).asText(null);
                if (value != null && !value.isBlank()) values.add(value);
            });
        }
        return values;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private record Token(int index, String word) {}
}
