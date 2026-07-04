package com.yanban.paper.literature;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.service.PaperModelClient;
import com.yanban.paper.service.PaperPromptService;
import com.yanban.paper.service.ResearchProfileResult;
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
public class LiteratureRerankService {

    private static final Logger log = LoggerFactory.getLogger(LiteratureRerankService.class);
    private static final int MAX_CANDIDATES_FOR_LLM = 40;

    private final PaperTaskAnalysisRepository analyses;
    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final ObjectMapper objectMapper;

    public LiteratureRerankService(PaperTaskAnalysisRepository analyses,
                                   PaperPromptService promptService,
                                   PaperModelClient modelClient,
                                   ObjectMapper objectMapper) {
        this.analyses = analyses;
        this.promptService = promptService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RerankResult rerank(PaperTask task,
                               ResearchProfileResult profile,
                               List<LiteratureSearchResult> ranked,
                               int minSelectionLimit,
                               int selectionLimit) {
        if (task == null || ranked == null || ranked.isEmpty() || selectionLimit <= 0) {
            return RerankResult.empty();
        }
        List<LiteratureSearchResult> candidates = ranked.stream().limit(MAX_CANDIDATES_FOR_LLM).toList();
        Set<Long> allowedCardIds = new LinkedHashSet<>();
        candidates.forEach(item -> allowedCardIds.add(item.card().getId()));
        try {
            PaperTaskAnalysis analysis = analyses.findByTaskId(task.getId()).orElse(null);
            String conceptLadderJson = analysis == null || analysis.getConceptLadderJson() == null ? "{}" : analysis.getConceptLadderJson();
            String prompt = promptService.render("literature-rerank", Map.of(
                    "paperContextJson", paperContextJson(task, profile),
                    "citationSlotsJson", citationSlotsJson(conceptLadderJson),
                    "candidateCardsJson", candidateCardsJson(candidates),
                    "minSelectionLimit", Math.min(minSelectionLimit, selectionLimit),
                    "selectionLimit", selectionLimit
            ));
            String text = modelClient.complete("You return strict JSON only and select only provided cardIds.", prompt, 0.1, 4096);
            RerankResult result = parse(text, allowedCardIds, selectionLimit);
            persistRerankResult(task.getId(), conceptLadderJson, result, text);
            return result;
        } catch (Exception ex) {
            log.debug("Literature LLM rerank failed for task {}: {}", task.getId(), ex.getMessage());
            return RerankResult.empty();
        }
    }

    private String paperContextJson(PaperTask task, ResearchProfileResult profile) throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("paperTitle", task.getTitle());
        context.put("targetLanguage", task.getTargetLanguage());
        context.put("researchProfile", profile == null ? Map.of() : objectMapper.convertValue(profile, new TypeReference<Map<String, Object>>() {}));
        return objectMapper.writeValueAsString(context);
    }

    private String citationSlotsJson(String conceptLadderJson) throws Exception {
        JsonNode root = objectMapper.readTree(conceptLadderJson == null || conceptLadderJson.isBlank() ? "{}" : conceptLadderJson);
        JsonNode slots = root.path("citationSlots");
        return objectMapper.writeValueAsString(slots.isArray() ? slots : objectMapper.createArrayNode());
    }

    private String candidateCardsJson(List<LiteratureSearchResult> candidates) throws Exception {
        List<Map<String, Object>> values = new ArrayList<>();
        for (LiteratureSearchResult result : candidates) {
            LiteratureCard card = result.card();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cardId", card.getId());
            item.put("ruleScore", result.relevanceScore());
            item.put("slotId", result.ladderNode());
            item.put("sourceQuery", result.sourceQuery());
            item.put("title", card.getTitle());
            item.put("year", card.getPublicationYear());
            item.put("venue", card.getVenue());
            item.put("doi", card.getDoi());
            item.put("url", card.getUrl());
            item.put("citationCount", card.getCitationCount());
            item.put("fieldsOfStudy", parseJson(card.getFieldsOfStudyJson(), List.of()));
            item.put("literatureCard", parseJson(card.getAnalysisJson(), Map.of()));
            values.add(item);
        }
        return objectMapper.writeValueAsString(values);
    }

    private Object parseJson(String raw, Object fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private RerankResult parse(String text, Set<Long> allowedCardIds, int selectionLimit) throws Exception {
        JsonNode root = objectMapper.readTree(extractJsonObject(text));
        JsonNode selected = root.path("selected");
        Set<Long> selectedIds = new LinkedHashSet<>();
        List<Map<String, Object>> selectedDetails = new ArrayList<>();
        if (selected.isArray()) {
            for (JsonNode item : selected) {
                if (selectedIds.size() >= selectionLimit) break;
                Long cardId = parseLong(item.path("cardId"));
                if (cardId == null || !allowedCardIds.contains(cardId) || !selectedIds.add(cardId)) continue;
                selectedDetails.add(objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {}));
            }
        }
        List<Map<String, Object>> rejectedDetails = new ArrayList<>();
        JsonNode rejected = root.path("rejected");
        if (rejected.isArray()) {
            for (JsonNode item : rejected) {
                rejectedDetails.add(objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {}));
            }
        }
        return new RerankResult(selectedIds, selectedDetails, rejectedDetails);
    }

    private void persistRerankResult(Long taskId, String conceptLadderJson, RerankResult result, String rawText) {
        try {
            PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElseGet(() -> new PaperTaskAnalysis(taskId));
            Map<String, Object> ladder = objectMapper.readValue(conceptLadderJson == null || conceptLadderJson.isBlank() ? "{}" : conceptLadderJson,
                    new TypeReference<Map<String, Object>>() {});
            ladder.put("literatureRerank", Map.of(
                    "generatedBy", "llm-literature-rerank-v1",
                    "selected", result.selectedDetails(),
                    "rejected", result.rejectedDetails(),
                    "rawText", rawText == null ? "" : truncate(rawText, 4000)
            ));
            analysis.setConceptLadderJson(objectMapper.writeValueAsString(ladder));
            analyses.save(analysis);
        } catch (Exception ex) {
            log.debug("Failed to persist literature rerank result for task {}: {}", taskId, ex.getMessage());
        }
    }

    private Long parseLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try {
            if (node.isNumber()) return node.asLong();
            return Long.parseLong(node.asText(""));
        } catch (Exception ex) {
            return null;
        }
    }

    private String extractJsonObject(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : "{}";
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max) + " ...";
    }

    public record RerankResult(Set<Long> selectedCardIds,
                               List<Map<String, Object>> selectedDetails,
                               List<Map<String, Object>> rejectedDetails) {
        public static RerankResult empty() {
            return new RerankResult(Set.of(), List.of(), List.of());
        }
    }
}
