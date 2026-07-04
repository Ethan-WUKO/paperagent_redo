package com.yanban.paper.literature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.config.PaperLiteratureProperties;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.service.PaperModelClient;
import com.yanban.paper.service.PaperPromptService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiteratureCardAnalysisService {

    public static final String MODEL_VERSION = "llm-literature-card-v1";
    private static final Logger log = LoggerFactory.getLogger(LiteratureCardAnalysisService.class);

    private final LiteratureCardRepository cards;
    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final LiteratureCardIndexService indexService;
    private final PaperLiteratureProperties properties;

    public LiteratureCardAnalysisService(LiteratureCardRepository cards,
                                         PaperPromptService promptService,
                                         PaperModelClient modelClient,
                                         ObjectMapper objectMapper,
                                         LiteratureCardIndexService indexService,
                                         PaperLiteratureProperties properties) {
        this.cards = cards;
        this.promptService = promptService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.indexService = indexService;
        this.properties = properties;
    }

    @Transactional
    public LiteratureCard analyzeIfNeeded(Long cardId) {
        LiteratureCard card = cards.findById(cardId).orElse(null);
        if (card == null) return null;
        if (MODEL_VERSION.equals(card.getAnalysisModelVersion()) && card.getAnalysisJson() != null && !card.getAnalysisJson().isBlank()) {
            indexService.index(card);
            return card;
        }
        Map<String, Object> analysis;
        try {
            analysis = llmAnalysis(card);
        } catch (Exception ex) {
            log.debug("Literature card LLM analysis failed for {}: {}", cardId, ex.getMessage());
            analysis = fallbackAnalysis(card, "fallback_after_llm_failure");
        }
        card.setAnalysisJson(toJson(analysis));
        card.setAnalysisModelVersion(MODEL_VERSION);
        card.setAnalyzedAt(Instant.now());
        LiteratureCard saved = cards.save(card);
        indexService.index(saved);
        return saved;
    }

    public void analyzeTopCandidates(List<LiteratureSearchResult> ranked) {
        if (ranked == null || ranked.isEmpty()) return;
        int limit = Math.max(0, properties.getMaxAnalysisPerTask());
        if (limit <= 0) return;
        Set<Long> seen = new LinkedHashSet<>();
        for (LiteratureSearchResult result : ranked) {
            if (seen.size() >= limit) break;
            Long cardId = result.card().getId();
            if (cardId == null || !seen.add(cardId)) continue;
            analyzeIfNeeded(cardId);
        }
    }

    private Map<String, Object> llmAnalysis(LiteratureCard card) throws JsonProcessingException {
        String metadataJson = objectMapper.writeValueAsString(metadata(card));
        String prompt = promptService.render("literature-card", Map.of(
                "metadataJson", metadataJson,
                "abstractText", truncate(card.getAbstractText(), 3000)
        ));
        String text = modelClient.complete("You return strict JSON only and do not invent details.", prompt, 0.1, 2048);
        JsonNode root = objectMapper.readTree(extractJsonObject(text));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("claim", root.path("claim").asText(""));
        result.put("problem", root.path("problem").asText(""));
        result.put("methods", stringList(root.path("methods")));
        result.put("tasks", stringList(root.path("tasks")));
        result.put("domainTerms", stringList(root.path("domainTerms")));
        result.put("evidenceUse", objectList(root.path("evidenceUse")));
        result.put("limitations", stringList(root.path("limitations")));
        result.put("bestUseInIntroduction", root.path("bestUseInIntroduction").asText(""));
        result.put("notUseFor", stringList(root.path("notUseFor")));
        result.put("generatedBy", MODEL_VERSION);
        return result;
    }

    public Map<String, Object> fallbackAnalysis(LiteratureCard card, String generatedBy) {
        String text = (card.getTitle() + " " + nullToEmpty(card.getAbstractText()) + " " + nullToEmpty(card.getFieldsOfStudyJson())).toLowerCase();
        List<String> domainTerms = domainTerms(text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("claim", firstSentence(card.getAbstractText()));
        result.put("problem", card.getTitle());
        result.put("methods", domainTerms);
        result.put("tasks", domainTerms);
        result.put("domainTerms", domainTerms);
        result.put("evidenceUse", List.of(Map.of("supports", "Potentially relevant background or related work; verify against the full paper before citing.", "strength", "LOW")));
        result.put("limitations", List.of("Generated from metadata/abstract only; verify full paper before using as evidence."));
        result.put("bestUseInIntroduction", "Use only after manual verification.");
        result.put("notUseFor", List.of("Do not use as strong evidence without checking the full paper."));
        result.put("generatedBy", generatedBy);
        return result;
    }

    private Map<String, Object> metadata(LiteratureCard card) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("cardId", card.getId());
        metadata.put("title", card.getTitle());
        metadata.put("authors", parseList(card.getAuthors()));
        metadata.put("year", card.getPublicationYear());
        metadata.put("venue", card.getVenue());
        metadata.put("doi", card.getDoi());
        metadata.put("arxivId", card.getArxivId());
        metadata.put("openAlexId", card.getOpenAlexId());
        metadata.put("fieldsOfStudy", parseList(card.getFieldsOfStudyJson()));
        return metadata;
    }

    private List<?> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<?>>() {});
        } catch (Exception ex) {
            return List.of(json);
        }
    }

    private List<String> domainTerms(String text) {
        Set<String> stop = Set.of("the", "and", "for", "with", "that", "this", "from", "using", "based", "paper", "method", "study", "system", "systems", "problem", "result", "results");
        Set<String> terms = new LinkedHashSet<>();
        for (String token : (text == null ? "" : text).replaceAll("[^a-z0-9-]+", " ").split("\\s+")) {
            String value = token.replaceAll("^-+|-+$", "");
            if (value.length() >= 4 && !stop.contains(value)) terms.add(value);
            if (terms.size() >= 12) break;
        }
        return List.copyOf(terms);
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").trim();
            if (!value.isBlank()) values.add(value);
        });
        return values;
    }

    private List<Object> objectList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Object> values = new ArrayList<>();
        node.forEach(item -> values.add(objectMapper.convertValue(item, Object.class)));
        return values;
    }

    private String extractJsonObject(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : "{}";
    }

    private String firstSentence(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        int dot = normalized.indexOf('.');
        return dot > 20 ? normalized.substring(0, dot + 1) : truncate(normalized, 240);
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + " ...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
