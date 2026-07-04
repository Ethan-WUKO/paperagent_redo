package com.yanban.paper.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.service.PaperModelClient;
import com.yanban.paper.service.PaperPromptService;
import com.yanban.paper.service.ResearchProfileResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class LiteratureQueryPlanner {

    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final ObjectMapper objectMapper;

    public LiteratureQueryPlanner(PaperPromptService promptService,
                                  ObjectProvider<PaperModelClient> modelClientProvider,
                                  ObjectMapper objectMapper) {
        this.promptService = promptService;
        this.modelClient = modelClientProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    public List<String> planQueries(String paperTitle, String targetLanguage, ResearchProfileResult profile, int limit) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (modelClient != null) {
            queries.addAll(llmQueries(paperTitle, targetLanguage, profile));
        }
        queries.addAll(fallbackQueries(profile));
        return queries.stream()
                .map(this::normalize)
                .filter(query -> !query.isBlank())
                .filter(query -> query.split("\\s+").length >= 2)
                .limit(Math.max(1, limit))
                .toList();
    }

    public List<String> fallbackQueries(ResearchProfileResult profile) {
        Set<String> queries = new LinkedHashSet<>();
        addIfUseful(queries, profile.problem());
        addIfUseful(queries, profile.method());
        profile.tasks().forEach(item -> addIfUseful(queries, item));
        profile.keywords().forEach(item -> addIfUseful(queries, item));
        if (!blank(profile.method()) && !blank(profile.problem())) {
            addIfUseful(queries, profile.method() + " " + profile.problem());
        }
        if (!profile.keywords().isEmpty() && !profile.tasks().isEmpty()) {
            addIfUseful(queries, profile.keywords().get(0) + " " + profile.tasks().get(0));
        }
        return List.copyOf(queries);
    }

    private List<String> llmQueries(String paperTitle, String targetLanguage, ResearchProfileResult profile) {
        try {
            String profileJson = objectMapper.writeValueAsString(profile);
            String prompt = promptService.render("literature-search-query", Map.of(
                    "targetLanguage", targetLanguage == null || targetLanguage.isBlank() ? "en" : targetLanguage,
                    "paperTitle", paperTitle == null ? "" : paperTitle,
                    "researchProfile", profileJson
            ));
            String text = modelClient.complete("You return strict JSON only.", prompt, 0.2, 2048);
            JsonNode root = objectMapper.readTree(extractJsonObject(text));
            JsonNode array = root.path("queries");
            if (!array.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            array.forEach(item -> addIfUseful(result, item.asText("")));
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String extractJsonObject(String text) {
        if (text == null) return "{}";
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    private void addIfUseful(Set<String> queries, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank() && normalized.length() >= 6 && !tooGeneric(normalized)) {
            queries.add(normalized);
        }
    }

    private void addIfUseful(List<String> queries, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank() && normalized.length() >= 6 && !tooGeneric(normalized)
                && queries.stream().noneMatch(item -> item.equalsIgnoreCase(normalized))) {
            queries.add(normalized);
        }
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("[_{}\\\\]", " ")
                .replaceAll("[\"'`]+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean tooGeneric(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return Set.of("radar", "optimization", "method", "experiment", "results", "signal model", "problem formulation").contains(q);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
