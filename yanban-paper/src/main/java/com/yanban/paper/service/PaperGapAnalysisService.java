package com.yanban.paper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskLiterature;
import com.yanban.paper.domain.PaperTaskLiteratureRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperGapAnalysisService {

    private final PaperTaskRepository tasks;
    private final PaperTaskAnalysisRepository analyses;
    private final PaperTaskLiteratureRepository taskLiterature;
    private final LiteratureCardRepository cards;
    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidenceRepository;
    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final ObjectMapper objectMapper;

    public PaperGapAnalysisService(PaperTaskRepository tasks,
                                   PaperTaskAnalysisRepository analyses,
                                   PaperTaskLiteratureRepository taskLiterature,
                                   LiteratureCardRepository cards,
                                   SuggestionRepository suggestions,
                                   SuggestionEvidenceRepository evidenceRepository,
                                   PaperPromptService promptService,
                                   PaperModelClient modelClient,
                                   ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.analyses = analyses;
        this.taskLiterature = taskLiterature;
        this.cards = cards;
        this.suggestions = suggestions;
        this.evidenceRepository = evidenceRepository;
        this.promptService = promptService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<GapSuggestionResult> generateAndSave(Long taskId, String structureSummary, String targetLanguage) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElseGet(() -> new PaperTaskAnalysis(taskId));
        List<PaperTaskLiterature> selectedRelations = taskLiterature.findByTaskIdOrderByRelevanceScoreDesc(taskId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getSelected()))
                .toList();
        Map<Long, LiteratureCard> selectedCards = cards.findAllById(selectedRelations.stream().map(PaperTaskLiterature::getCardId).toList())
                .stream()
                .collect(Collectors.toMap(LiteratureCard::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        String prompt = promptService.render("gap-analysis", Map.of(
                "targetLanguage", blankToDefault(targetLanguage, task.getTargetLanguage()),
                "paperTitle", task.getTitle(),
                "researchProfile", blankToDefault(analysis.getResearchProfileJson(), "{}"),
                "conceptLadder", blankToDefault(analysis.getConceptLadderJson(), "{}"),
                "literatureCandidates", renderCandidates(selectedRelations, selectedCards),
                "structureSummary", blankToDefault(structureSummary, "No additional structure issues provided.")
        ));
        String text;
        try {
            text = modelClient.complete("You return strict JSON only and never invent evidence.", prompt, 0.2, 4096);
        } catch (Exception ex) {
            text = "";
        }
        List<GapSuggestionResult> results = parseAndSave(taskId, text, selectedCards.keySet());
        if (results.isEmpty() && !selectedCards.isEmpty()) {
            results = createFallbackSuggestions(taskId, selectedRelations, selectedCards);
        }
        analysis.setGapMatrixJson(toJson(Map.of(
                "generatedBy", "gap-analysis-v1",
                "suggestionCount", results.size(),
                "suggestions", results
        )));
        analyses.save(analysis);
        return results;
    }

    @Transactional
    public List<GapSuggestionResult> parseAndSave(Long taskId, String modelText, Set<Long> allowedCardIds) {
        List<Suggestion> existing = suggestions.findByTaskIdOrderByCreatedAt(taskId);
        if (!existing.isEmpty()) {
            evidenceRepository.deleteBySuggestionIdIn(existing.stream().map(Suggestion::getId).toList());
            suggestions.deleteByTaskId(taskId);
            suggestions.flush();
        }

        JsonNode root = readRoot(modelText);
        JsonNode array = root.path("suggestions");
        if (!array.isArray()) {
            return List.of();
        }
        List<GapSuggestionResult> results = new ArrayList<>();
        for (JsonNode node : array) {
            NormalizedSuggestion normalized = normalize(node, allowedCardIds == null ? Set.of() : allowedCardIds);
            Suggestion suggestion = new Suggestion(taskId, normalized.track(), normalized.category(), normalized.statement());
            suggestion.setSeverity(normalized.severity());
            suggestion.setApplicable(normalized.applicable());
            suggestion.setPatchJson(normalized.patch().isEmpty() ? null : toJson(normalized.patch()));
            suggestion = suggestions.save(suggestion);
            for (Long cardId : normalized.evidenceCardIds()) {
                evidenceRepository.save(new SuggestionEvidence(suggestion.getId(), cardId));
            }
            results.add(new GapSuggestionResult(
                    suggestion.getId(),
                    suggestion.getTrack(),
                    suggestion.getCategory(),
                    suggestion.getSeverity(),
                    suggestion.getStatement(),
                    normalized.evidenceCardIds(),
                    Boolean.TRUE.equals(suggestion.getApplicable()),
                    normalized.patch()
            ));
        }
        return results;
    }

    private NormalizedSuggestion normalize(JsonNode node, Set<Long> allowedCardIds) {
        String track = uppercaseOrDefault(text(node, "track"), "CRITIQUE");
        if (!track.equals("ADVOCACY") && !track.equals("CRITIQUE")) {
            track = "CRITIQUE";
        }
        String category = blankToDefault(text(node, "category"), "General");
        String severity = blankToDefault(text(node, "severity"), "minor").toLowerCase(Locale.ROOT);
        String statement = blankToDefault(text(node, "statement"), "No statement provided.");
        List<Long> evidenceCardIds = validEvidence(node.path("evidence"), allowedCardIds);
        boolean hasGrounding = !evidenceCardIds.isEmpty();
        boolean requestedApplicable = node.path("applicable").asBoolean(false);
        Map<String, Object> patch = patchMap(node.path("patch"));

        if (!hasGrounding && "ADVOCACY".equals(track)) {
            track = "CRITIQUE";
            requestedApplicable = false;
            patch = Map.of();
            statement = "[Converted from ungrounded advocacy] " + statement;
        }
        if (!"ADVOCACY".equals(track)) {
            requestedApplicable = false;
            patch = Map.of();
        }
        if ("ADVOCACY".equals(track) && requestedApplicable && patch.isEmpty()) {
            requestedApplicable = false;
        }
        return new NormalizedSuggestion(track, category, severity, statement, evidenceCardIds, requestedApplicable, patch);
    }

    private List<Long> validEvidence(JsonNode evidence, Set<Long> allowedCardIds) {
        if (!evidence.isArray()) return List.of();
        Set<Long> valid = new LinkedHashSet<>();
        for (JsonNode item : evidence) {
            Long cardId = parseCardId(item.asText(""));
            if (cardId != null && allowedCardIds.contains(cardId)) {
                valid.add(cardId);
            }
        }
        return List.copyOf(valid);
    }

    private Long parseCardId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.startsWith("card-")) value = value.substring("card-".length());
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> patchMap(JsonNode patch) {
        if (patch == null || !patch.isObject()) return Map.of();
        try {
            return objectMapper.convertValue(patch, new TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException ex) {
            return Map.of();
        }
    }

    private JsonNode readRoot(String modelText) {
        if (modelText == null || modelText.isBlank()) {
            return objectMapper.createObjectNode();
        }
        String json = extractJsonObject(modelText);
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
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

    private List<GapSuggestionResult> createFallbackSuggestions(Long taskId, List<PaperTaskLiterature> selectedRelations, Map<Long, LiteratureCard> selectedCards) {
        List<PaperTaskLiterature> ordered = selectedRelations.stream()
                .filter(relation -> selectedCards.containsKey(relation.getCardId()))
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        List<Long> advocacyEvidence = ordered.stream().limit(3).map(PaperTaskLiterature::getCardId).toList();
        Suggestion advocacy = new Suggestion(taskId, "ADVOCACY", "Literature grounding", "Consider citing the retrieved related work to strengthen the motivation and positioning of the paper. Verify each citation before adding it to the manuscript.");
        advocacy.setSeverity("minor");
        advocacy.setApplicable(false);
        advocacy = suggestions.save(advocacy);
        for (Long cardId : advocacyEvidence) {
            evidenceRepository.save(new SuggestionEvidence(advocacy.getId(), cardId));
        }

        List<Long> critiqueEvidence = ordered.stream().skip(3).limit(3).map(PaperTaskLiterature::getCardId).toList();
        if (critiqueEvidence.isEmpty()) {
            critiqueEvidence = advocacyEvidence;
        }
        Suggestion critique = new Suggestion(taskId, "CRITIQUE", "Positioning check", "The current draft should explicitly compare its assumptions, objectives, and evaluation protocol against the retrieved related work rather than only listing citations.");
        critique.setSeverity("major");
        critique.setApplicable(false);
        critique = suggestions.save(critique);
        for (Long cardId : critiqueEvidence) {
            evidenceRepository.save(new SuggestionEvidence(critique.getId(), cardId));
        }
        return List.of(
                new GapSuggestionResult(advocacy.getId(), advocacy.getTrack(), advocacy.getCategory(), advocacy.getSeverity(), advocacy.getStatement(), advocacyEvidence, false, Map.of()),
                new GapSuggestionResult(critique.getId(), critique.getTrack(), critique.getCategory(), critique.getSeverity(), critique.getStatement(), critiqueEvidence, false, Map.of())
        );
    }

    private String renderCandidates(List<PaperTaskLiterature> relations, Map<Long, LiteratureCard> selectedCards) {
        StringBuilder builder = new StringBuilder();
        for (PaperTaskLiterature relation : relations) {
            LiteratureCard card = selectedCards.get(relation.getCardId());
            if (card == null) continue;
            builder.append("- card-").append(card.getId())
                    .append(" | score=").append(relation.getRelevanceScore())
                    .append(" | role=").append(relation.getNarrativeRole())
                    .append(" | slot=").append(relation.getLadderNode())
                    .append(" | sourceQuery=").append(relation.getSourceQuery())
                    .append(" | title=").append(card.getTitle())
                    .append(" | year=").append(card.getPublicationYear())
                    .append(" | venue=").append(card.getVenue())
                    .append("\n  abstract=").append(truncate(card.getAbstractText(), 700))
                    .append("\n  analysis=").append(truncate(card.getAnalysisJson(), 700))
                    .append('\n');
        }
        return builder.isEmpty() ? "No selected literature cards." : builder.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private String uppercaseOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + " ...";
    }

    private record NormalizedSuggestion(
            String track,
            String category,
            String severity,
            String statement,
            List<Long> evidenceCardIds,
            boolean applicable,
            Map<String, Object> patch
    ) {
    }
}
