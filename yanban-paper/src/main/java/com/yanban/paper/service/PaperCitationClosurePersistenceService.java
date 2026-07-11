package com.yanban.paper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidenceId;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists citation-closure decisions after all model calls have completed. */
@Service
public class PaperCitationClosurePersistenceService {

    private static final int PATCH_JSON_LIMIT = 9_800;

    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidenceRepository;
    private final PaperTaskAnalysisRepository analyses;
    private final ObjectMapper objectMapper;

    public PaperCitationClosurePersistenceService(SuggestionRepository suggestions,
                                                  SuggestionEvidenceRepository evidenceRepository,
                                                  PaperTaskAnalysisRepository analyses,
                                                  ObjectMapper objectMapper) {
        this.suggestions = suggestions;
        this.evidenceRepository = evidenceRepository;
        this.analyses = analyses;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void persist(Long taskId, PaperCitationClosureService.ClosureResult result) {
        if (result == null) return;
        Map<Long, Suggestion> persisted = suggestions.findAllById(result.outcomes().stream()
                        .map(PaperCitationClosureService.ClosureOutcome::suggestionId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(Suggestion::getId, Function.identity()));

        for (PaperCitationClosureService.ClosureOutcome outcome : result.outcomes()) {
            Suggestion suggestion = persisted.get(outcome.suggestionId());
            if (suggestion == null || !taskId.equals(suggestion.getTaskId())) continue;

            Map<String, Object> patch = new LinkedHashMap<>(readMap(suggestion.getPatchJson()));
            Map<String, Object> closure = closureMetadata(outcome, true);
            patch.put("citationClosure", closure);
            if (outcome.accepted()) {
                patch.put("anchor", outcome.citationAnchor());
                patch.put("sectionOrder", outcome.sectionOrder());
                patch.put("sectionTitle", outcome.sectionTitle());
                patch.put("citationCritic", outcome.finalCritic());
                patch.put("citationScope", "CLOSURE_PATCH");
                patch.put("decisionSource", "AUTO_CITATION_CLOSURE");
                patch.remove("applicationDecision");
                suggestion.setApplicable(true);
                suggestion.setStatus("ACCEPTED");
                retainAcceptedEvidence(outcome);
            } else {
                suggestion.setApplicable(false);
                suggestion.setStatus("PROPOSED");
            }
            suggestion.setPatchJson(compactPatch(patch, outcome));
            suggestions.save(suggestion);
        }

        analyses.findByTaskId(taskId).ifPresent(analysis -> {
            Map<String, Object> matrix = new LinkedHashMap<>(readMap(analysis.getGapMatrixJson()));
            matrix.put("citationClosureLoop", result.summary());
            analysis.setGapMatrixJson(writeJson(matrix));
            analyses.save(analysis);
        });
    }

    private void retainAcceptedEvidence(PaperCitationClosureService.ClosureOutcome outcome) {
        Set<Long> accepted = new LinkedHashSet<>(outcome.acceptedEvidenceCardIds());
        evidenceRepository.findBySuggestionId(outcome.suggestionId()).stream()
                .map(item -> item.getCardId())
                .filter(cardId -> !accepted.contains(cardId))
                .forEach(cardId -> evidenceRepository.deleteById(
                        new SuggestionEvidenceId(outcome.suggestionId(), cardId)));
    }

    private Map<String, Object> closureMetadata(PaperCitationClosureService.ClosureOutcome outcome,
                                                boolean includeRounds) {
        Map<String, Object> closure = new LinkedHashMap<>();
        closure.put("status", outcome.accepted() ? "SUPPORTED" : "REPORT_ONLY");
        closure.put("attempts", outcome.attempts());
        closure.put("operation", outcome.operation());
        closure.put("reason", outcome.reason());
        closure.put("originalAnchor", outcome.originalAnchor());
        closure.put("replacementText", outcome.replacementText());
        closure.put("citationAnchor", outcome.citationAnchor());
        closure.put("acceptedEvidenceCardIds", outcome.acceptedEvidenceCardIds());
        if (includeRounds) closure.put("rounds", outcome.rounds());
        return closure;
    }

    private String compactPatch(Map<String, Object> patch,
                                PaperCitationClosureService.ClosureOutcome outcome) {
        String json = writeJson(patch);
        if (json.length() <= PATCH_JSON_LIMIT) return json;

        Map<String, Object> closure = closureMetadata(outcome, false);
        closure.put("roundsOmitted", true);
        patch.put("citationClosure", closure);
        json = writeJson(patch);
        if (json.length() <= PATCH_JSON_LIMIT) return json;

        Map<String, Object> compact = new LinkedHashMap<>();
        copyIfPresent(patch, compact, "targetSlotId");
        copyIfPresent(patch, compact, "slotMatchMode");
        copyIfPresent(patch, compact, "sectionOrder");
        copyIfPresent(patch, compact, "sectionTitle");
        copyIfPresent(patch, compact, "anchor");
        copyIfPresent(patch, compact, "citationScope");
        copyIfPresent(patch, compact, "decisionSource");
        Map<String, Object> critic = compactCritic(patch.get("citationCritic"));
        if (!critic.isEmpty()) compact.put("citationCritic", critic);
        copyIfPresent(patch, compact, "applicationDecision");
        closure.put("reason", truncate(outcome.reason(), 500));
        compact.put("citationClosure", closure);
        json = writeJson(compact);
        if (json.length() <= PATCH_JSON_LIMIT) return json;

        closure.remove("reason");
        critic.remove("reason");
        json = writeJson(compact);
        if (json.length() <= PATCH_JSON_LIMIT) return json;

        compact.remove("anchor");
        compact.remove("sectionTitle");
        compact.remove("applicationDecision");
        critic.remove("supportedAnchor");
        json = writeJson(compact);
        if (json.length() > PATCH_JSON_LIMIT) {
            throw new IllegalStateException("Citation closure patch exceeds the persisted metadata limit");
        }
        return json;
    }

    private Map<String, Object> compactCritic(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return new LinkedHashMap<>();
        Map<String, Object> critic = new LinkedHashMap<>();
        copyRawIfPresent(raw, critic, "verdict");
        copyRawIfPresent(raw, critic, "acceptedEvidenceCardIds");
        copyRawIfPresent(raw, critic, "supportedAnchor");
        if (raw.containsKey("reason")) critic.put("reason", truncate(String.valueOf(raw.get("reason")), 500));
        return critic;
    }

    private void copyRawIfPresent(Map<?, ?> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist citation closure metadata", ex);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength) + " ...";
    }
}
