package com.yanban.paper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexSection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperResearchProfileService {

    private final PaperTaskRepository tasks;
    private final PaperTaskAnalysisRepository analysisRepository;
    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final ObjectMapper objectMapper;

    public PaperResearchProfileService(PaperTaskRepository tasks,
                                       PaperTaskAnalysisRepository analysisRepository,
                                       PaperPromptService promptService,
                                       PaperModelClient modelClient,
                                       ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.analysisRepository = analysisRepository;
        this.promptService = promptService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ResearchProfileResult generateAndSave(Long taskId, LatexDocument document, String targetLanguage) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        String prompt = promptService.render("research-profile", Map.of(
                "targetLanguage", blankToDefault(targetLanguage, task.getTargetLanguage()),
                "paperTitle", blankToDefault(document.title(), task.getTitle()),
                "sectionSummaries", summarizeSections(document.sections())
        ));
        String text;
        try {
            text = modelClient.complete("You return strict JSON only.", prompt, 0.2, 2048);
        } catch (Exception ex) {
            text = "";
        }
        ResearchProfileResult result = parseResult(text);
        if (isEmpty(result)) {
            result = fallbackProfile(task, document, text);
        }
        PaperTaskAnalysis analysis = analysisRepository.findByTaskId(taskId).orElseGet(() -> new PaperTaskAnalysis(taskId));
        analysis.setResearchProfileJson(toJson(result));
        analysisRepository.save(analysis);
        return result;
    }

    public ResearchProfileResult parseResult(String text) {
        if (text == null || text.isBlank()) {
            return degraded("{}");
        }
        String json = extractJsonObject(text);
        try {
            JsonNode root = objectMapper.readTree(json);
            return new ResearchProfileResult(
                    textOrEmpty(root, "problem"),
                    textOrEmpty(root, "method"),
                    stringList(root, "contributions"),
                    stringList(root, "datasets"),
                    stringList(root, "baselines"),
                    stringList(root, "metrics"),
                    stringList(root, "tasks"),
                    stringList(root, "keywords"),
                    false,
                    text
            );
        } catch (Exception ex) {
            return degraded(text);
        }
    }

    private ResearchProfileResult degraded(String raw) {
        return new ResearchProfileResult("", "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true, raw);
    }

    private boolean isEmpty(ResearchProfileResult result) {
        return result.problem().isBlank()
                && result.method().isBlank()
                && result.tasks().isEmpty()
                && result.keywords().isEmpty();
    }

    private ResearchProfileResult fallbackProfile(PaperTask task, LatexDocument document, String rawText) {
        List<String> keywords = new ArrayList<>();
        addKeyword(keywords, document.title());
        addKeyword(keywords, task.getTitle());
        for (LatexSection section : document.sections()) {
            String title = section.title() == null ? "" : section.title().trim();
            if (!title.isBlank() && !List.of("introduction", "conclusion", "references").contains(title.toLowerCase())) {
                addKeyword(keywords, title);
            }
            if (keywords.size() >= 8) break;
        }
        String problem = keywords.isEmpty() ? blankToDefault(document.title(), task.getTitle()) : keywords.get(0);
        String method = keywords.size() > 1 ? keywords.get(1) : problem;
        return new ResearchProfileResult(problem, method, List.of(), List.of(), List.of(), List.of(), keywords, keywords, true,
                rawText == null || rawText.isBlank() ? "fallback-from-title-and-section-headings" : rawText);
    }

    private void addKeyword(List<String> keywords, String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.replaceAll("[_{}\\\\]", " ").replaceAll("\\s+", " ").trim();
        if (!normalized.isBlank() && keywords.stream().noneMatch(item -> item.equalsIgnoreCase(normalized))) {
            keywords.add(normalized);
        }
    }

    private String summarizeSections(List<LatexSection> sections) {
        StringBuilder builder = new StringBuilder();
        for (LatexSection section : sections) {
            String raw = section.rawText() == null ? "" : section.rawText().replaceAll("\\s+", " ").trim();
            if (raw.length() > 1200) {
                raw = raw.substring(0, 1200) + " ...";
            }
            builder.append("- [")
                    .append(section.orderIndex())
                    .append("] ")
                    .append(section.title())
                    .append(" (role=")
                    .append(section.role())
                    .append(")\n")
                    .append(raw)
                    .append("\n\n");
        }
        return builder.toString();
    }

    private String extractJsonObject(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String toJson(ResearchProfileResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            return "{\"degraded\":true,\"rawText\":\"serialization_failed\"}";
        }
    }

    private String textOrEmpty(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private List<String> stringList(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").trim();
            if (!value.isBlank()) values.add(value);
        });
        return values;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }
}
