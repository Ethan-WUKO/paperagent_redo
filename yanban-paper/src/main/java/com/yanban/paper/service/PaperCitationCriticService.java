package com.yanban.paper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Bounded, independent evidence review before citation slots are activated. */
@Service
public class PaperCitationCriticService {

    private static final int BATCH_SIZE = 4;
    private static final int MAX_ATTEMPTS_PER_BATCH = 2;
    private static final Set<String> VERDICTS = Set.of("SUPPORTED", "PARTIAL", "REJECTED");

    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final ObjectMapper objectMapper;

    public PaperCitationCriticService(PaperPromptService promptService,
                                      PaperModelClient modelClient,
                                      ObjectMapper objectMapper) {
        this.promptService = promptService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public CitationCriticResult review(List<GapSuggestionResult> suggestions,
                                       Map<Long, LiteratureCard> cards) {
        List<GapSuggestionResult> candidates = suggestions == null ? List.of() : suggestions.stream()
                .filter(item -> item != null
                        && item.suggestionId() != null
                        && item.applicable()
                        && "ADVOCACY".equalsIgnoreCase(item.track())
                        && item.evidenceCardIds() != null
                        && !item.evidenceCardIds().isEmpty())
                .toList();
        if (candidates.isEmpty()) {
            return new CitationCriticResult(
                    Map.of(), false, "No auto-applicable citation candidates.", "PASS", List.of());
        }

        Map<Long, CitationDecision> decisions = new LinkedHashMap<>();
        List<BatchDiagnostic> diagnostics = new ArrayList<>();
        int batchIndex = 1;
        for (int start = 0; start < candidates.size(); start += BATCH_SIZE) {
            List<GapSuggestionResult> batch = candidates.subList(start, Math.min(start + BATCH_SIZE, candidates.size()));
            int currentBatch = batchIndex++;
            BatchReview review;
            try {
                review = reviewBatch(currentBatch, batch, cards == null ? Map.of() : cards);
            } catch (Exception ex) {
                review = failedBatch(currentBatch, batch.stream().map(GapSuggestionResult::suggestionId).toList(),
                        0, "", "Citation critic batch setup failed: " + rootMessage(ex));
            }
            decisions.putAll(review.decisions());
            diagnostics.add(review.diagnostic());
        }

        long failedBatches = diagnostics.stream().filter(item -> !item.success()).count();
        String closureStatus = failedBatches == 0
                ? "PASS"
                : failedBatches == diagnostics.size() ? "DEGRADED" : "PARTIAL";
        String message = switch (closureStatus) {
            case "PASS" -> "All citation critic batches completed.";
            case "PARTIAL" -> "Some citation critic batches failed; successful batches were preserved.";
            default -> "All citation critic batches failed; automatic citation insertion was withheld.";
        };
        return new CitationCriticResult(
                Map.copyOf(decisions), failedBatches > 0, message, closureStatus, List.copyOf(diagnostics));
    }

    private BatchReview reviewBatch(int batchIndex,
                                    List<GapSuggestionResult> batch,
                                    Map<Long, LiteratureCard> cards) {
        Map<Long, Set<Long>> allowedCards = allowedCards(batch, cards);
        Map<Long, String> targetClaims = new LinkedHashMap<>();
        for (GapSuggestionResult candidate : batch) {
            targetClaims.put(candidate.suggestionId(), string(value(candidate.patch(), "anchor")));
        }
        List<Long> expectedIds = batch.stream().map(GapSuggestionResult::suggestionId).toList();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("batchIndex", batchIndex);
        envelope.put("expectedSuggestionIds", expectedIds);
        envelope.put("candidates", payload(batch, cards, allowedCards));
        String basePrompt = promptService.render("citation-critic", Map.of("candidates", toJson(envelope)));

        String previousResponse = "";
        String lastResponse = "";
        String lastError = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_BATCH; attempt++) {
            String prompt = attempt == 1
                    ? basePrompt
                    : repairPrompt(basePrompt, expectedIds, previousResponse, lastError);
            try {
                lastResponse = modelClient.complete(
                        attempt == 1
                                ? "You are an independent academic citation critic. Return strict JSON only and fail closed on uncertain support."
                                : "Repair the previous citation-critic response. Return one strict JSON object only.",
                        prompt,
                        0.0,
                        4096);
                BatchParseResult parsed = parseBatch(lastResponse, allowedCards, targetClaims);
                if (parsed.valid()) {
                    String parseMode = attempt == 1 ? "COMPLETE_JSON" : "REPAIRED_JSON";
                    return new BatchReview(
                            parsed.decisions(),
                            new BatchDiagnostic(batchIndex, expectedIds, attempt, parseMode,
                                    truncate(lastResponse, 2400), "", true));
                }
                lastError = parsed.errorReason();
                previousResponse = lastResponse;
            } catch (Exception ex) {
                lastError = rootMessage(ex);
                previousResponse = lastResponse;
            }
        }

        String reason = "Citation critic batch " + batchIndex + " failed after "
                + MAX_ATTEMPTS_PER_BATCH + " attempts: " + blankToDefault(lastError, "invalid response");
        return failedBatch(batchIndex, expectedIds, MAX_ATTEMPTS_PER_BATCH, lastResponse, reason);
    }

    private BatchReview failedBatch(int batchIndex,
                                    List<Long> expectedIds,
                                    int attemptCount,
                                    String lastResponse,
                                    String reason) {
        Map<Long, CitationDecision> unavailable = new LinkedHashMap<>();
        for (Long suggestionId : expectedIds) {
            unavailable.put(suggestionId, CitationDecision.unreviewed(suggestionId, reason));
        }
        return new BatchReview(
                Map.copyOf(unavailable),
                new BatchDiagnostic(batchIndex, expectedIds, attemptCount, "FAILED",
                        truncate(lastResponse, 2400), reason, false));
    }

    private Map<Long, Set<Long>> allowedCards(List<GapSuggestionResult> batch,
                                               Map<Long, LiteratureCard> cards) {
        Map<Long, Set<Long>> result = new LinkedHashMap<>();
        for (GapSuggestionResult candidate : batch) {
            Set<Long> ids = new LinkedHashSet<>();
            for (Long cardId : candidate.evidenceCardIds()) {
                if (cardId != null && cards.containsKey(cardId)) ids.add(cardId);
            }
            result.put(candidate.suggestionId(), ids);
        }
        return result;
    }

    private List<Map<String, Object>> payload(List<GapSuggestionResult> batch,
                                               Map<Long, LiteratureCard> cards,
                                               Map<Long, Set<Long>> allowedCards) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (GapSuggestionResult candidate : batch) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("suggestionId", candidate.suggestionId());
            item.put("statement", candidate.statement());
            item.put("targetSlotId", value(candidate.patch(), "targetSlotId"));
            item.put("targetClaim", value(candidate.patch(), "anchor"));
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (Long cardId : allowedCards.getOrDefault(candidate.suggestionId(), Set.of())) {
                LiteratureCard card = cards.get(cardId);
                Map<String, Object> cardPayload = new LinkedHashMap<>();
                cardPayload.put("cardId", cardId);
                cardPayload.put("title", card.getTitle());
                cardPayload.put("venue", card.getVenue());
                cardPayload.put("year", card.getPublicationYear());
                cardPayload.put("abstract", truncate(card.getAbstractText(), 1200));
                cardPayload.put("evidenceAnalysis", truncate(card.getAnalysisJson(), 800));
                evidence.add(cardPayload);
            }
            item.put("evidence", evidence);
            payload.add(item);
        }
        return payload;
    }

    private BatchParseResult parseBatch(String response,
                                        Map<Long, Set<Long>> allowedCards,
                                        Map<Long, String> targetClaims) {
        Map<String, Object> raw = readMap(response);
        Object values = raw.get("decisions");
        if (!(values instanceof List<?> list)) {
            return BatchParseResult.invalid("Citation critic returned no decisions array.");
        }
        Map<Long, CitationDecision> result = new LinkedHashMap<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
                return BatchParseResult.invalid("Citation critic returned a non-object decision item.");
            }
            Long suggestionId = longValue(map.get("suggestionId"));
            if (suggestionId == null || !allowedCards.containsKey(suggestionId)) {
                return BatchParseResult.invalid("Citation critic returned an unknown suggestionId: " + suggestionId);
            }
            if (result.containsKey(suggestionId)) {
                return BatchParseResult.invalid("Citation critic returned duplicate decisions for suggestionId " + suggestionId + ".");
            }
            result.put(suggestionId, normalizeDecision(
                    suggestionId, map, allowedCards.get(suggestionId), targetClaims.getOrDefault(suggestionId, "")));
        }
        Set<Long> missing = new LinkedHashSet<>(allowedCards.keySet());
        missing.removeAll(result.keySet());
        if (!missing.isEmpty()) {
            return BatchParseResult.invalid("Citation critic omitted expected suggestionIds: " + missing);
        }
        return BatchParseResult.valid(Map.copyOf(result));
    }

    private CitationDecision normalizeDecision(Long suggestionId,
                                               Map<?, ?> map,
                                               Set<Long> allowedCardIds,
                                               String targetClaim) {
        String verdict = string(map.get("verdict")).toUpperCase(Locale.ROOT);
        if (!VERDICTS.contains(verdict)) verdict = "REJECTED";
        Set<Long> accepted = new LinkedHashSet<>();
        Object acceptedValues = map.get("acceptedEvidenceCardIds");
        if (acceptedValues instanceof List<?> ids) {
            for (Object id : ids) {
                Long cardId = longValue(id);
                if (cardId != null && allowedCardIds.contains(cardId)) accepted.add(cardId);
            }
        }
        String supportedAnchor = "PARTIAL".equals(verdict)
                ? safePartialAnchor(targetClaim, string(map.get("supportedAnchor")))
                : "";
        if (("SUPPORTED".equals(verdict) || "PARTIAL".equals(verdict)) && accepted.isEmpty()) verdict = "REJECTED";
        if ("PARTIAL".equals(verdict) && supportedAnchor.isBlank()) accepted.clear();
        if ("REJECTED".equals(verdict)) accepted.clear();
        String reason = string(map.get("reason"));
        if (reason.isBlank()) reason = "No claim-level justification was supplied by the citation critic.";
        return new CitationDecision(suggestionId, verdict, List.copyOf(accepted), supportedAnchor, reason);
    }

    private String safePartialAnchor(String targetClaim, String candidate) {
        if (targetClaim == null || candidate == null || candidate.trim().length() < 20) return "";
        String anchor = candidate.trim();
        int first = targetClaim.indexOf(anchor);
        if (first < 0 || targetClaim.indexOf(anchor, first + anchor.length()) >= 0) return "";
        int before = previousNonWhitespace(targetClaim, first - 1);
        int after = nextNonWhitespace(targetClaim, first + anchor.length());
        if ((before >= 0 && !isClauseBoundary(targetClaim.charAt(before)))
                || (after < targetClaim.length() && !isClauseBoundary(targetClaim.charAt(after)))) {
            return "";
        }
        return anchor;
    }

    private int previousNonWhitespace(String value, int index) {
        while (index >= 0 && Character.isWhitespace(value.charAt(index))) index--;
        return index;
    }

    private int nextNonWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private boolean isClauseBoundary(char value) {
        return ",;:.!?()[]".indexOf(value) >= 0;
    }

    private String repairPrompt(String basePrompt,
                                List<Long> expectedIds,
                                String previousResponse,
                                String errorReason) {
        return basePrompt
                + "\n\nREPAIR THIS BATCH ONLY. Do not re-review any other batch."
                + "\nExpected suggestionIds: " + expectedIds
                + "\nValidation error: " + blankToDefault(errorReason, "invalid JSON structure")
                + "\nPrevious response:\n" + truncate(previousResponse, 4000)
                + "\nReturn exactly one JSON object with a decisions array containing every expected suggestionId exactly once.";
    }

    private Map<String, Object> readMap(String text) {
        try {
            String source = text == null ? "{}" : text.trim();
            int start = source.indexOf('{');
            int end = source.lastIndexOf('}');
            return objectMapper.readValue(start >= 0 && end > start ? source.substring(start, end + 1) : "{}",
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize citation critic input", ex);
        }
    }

    private Object value(Map<String, Object> values, String key) {
        return values == null ? "" : values.getOrDefault(key, "");
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        String text = string(value).replace("card-", "");
        try {
            return text.isBlank() ? null : Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + " ...";
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record CitationDecision(Long suggestionId,
                                   String verdict,
                                   List<Long> acceptedEvidenceCardIds,
                                   String supportedAnchor,
                                   String reason) {
        public static CitationDecision unreviewed(Long suggestionId, String reason) {
            return new CitationDecision(suggestionId, "UNREVIEWED", List.of(), "", reason);
        }

        public boolean supported() {
            return !acceptedEvidenceCardIds.isEmpty()
                    && ("SUPPORTED".equals(verdict)
                    || ("PARTIAL".equals(verdict) && supportedAnchor != null && !supportedAnchor.isBlank()));
        }

        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("verdict", verdict);
            result.put("acceptedEvidenceCardIds", acceptedEvidenceCardIds);
            result.put("supportedAnchor", supportedAnchor == null ? "" : supportedAnchor);
            result.put("reason", reason);
            return result;
        }
    }

    public record BatchDiagnostic(int batchIndex,
                                  List<Long> suggestionIds,
                                  int attemptCount,
                                  String parseMode,
                                  String rawResponsePreview,
                                  String errorReason,
                                  boolean success) {
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("batchIndex", batchIndex);
            result.put("suggestionIds", suggestionIds);
            result.put("attemptCount", attemptCount);
            result.put("parseMode", parseMode);
            result.put("rawResponsePreview", rawResponsePreview);
            result.put("errorReason", errorReason);
            result.put("success", success);
            return result;
        }
    }

    public record CitationCriticResult(Map<Long, CitationDecision> decisions,
                                       boolean degraded,
                                       String message,
                                       String closureStatus,
                                       List<BatchDiagnostic> batches) {
        public Map<String, Object> summary() {
            long supported = decisions.values().stream().filter(CitationDecision::supported).count();
            long failedBatches = batches.stream().filter(item -> !item.success()).count();
            long retriedBatches = batches.stream().filter(item -> item.attemptCount() > 1).count();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("generatedBy", "paper-citation-critic-v2-batched");
            result.put("closureStatus", closureStatus);
            result.put("degraded", degraded);
            result.put("candidateCount", decisions.size());
            result.put("supportedCount", supported);
            result.put("withheldCount", decisions.size() - supported);
            result.put("batchCount", batches.size());
            result.put("successfulBatchCount", batches.size() - failedBatches);
            result.put("failedBatchCount", failedBatches);
            result.put("retriedBatchCount", retriedBatches);
            result.put("message", message);
            result.put("batches", batches.stream().map(BatchDiagnostic::asMap).toList());
            return result;
        }
    }

    private record BatchReview(Map<Long, CitationDecision> decisions, BatchDiagnostic diagnostic) {}

    private record BatchParseResult(boolean valid,
                                    Map<Long, CitationDecision> decisions,
                                    String errorReason) {
        private static BatchParseResult valid(Map<Long, CitationDecision> decisions) {
            return new BatchParseResult(true, decisions, "");
        }

        private static BatchParseResult invalid(String reason) {
            return new BatchParseResult(false, Map.of(), reason);
        }
    }
}
