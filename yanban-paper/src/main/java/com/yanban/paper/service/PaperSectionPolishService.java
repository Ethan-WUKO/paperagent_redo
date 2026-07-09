package com.yanban.paper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.latex.LatexLintIssue;
import com.yanban.paper.latex.LatexMaskingService;
import com.yanban.paper.latex.LatexMaskingService.PlaceholderValidation;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.LatexSectionRole;
import com.yanban.paper.latex.MaskedLatexText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperSectionPolishService {

    private static final Pattern OUTPUT_TAG = Pattern.compile("<output>(.*?)</output>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STRUCTURAL_COMMAND = Pattern.compile(
            "\\\\(?:cite|citep|citet|citeauthor|citeyear|parencite|textcite|autocite|ref|eqref|cref|Cref|autoref|pageref|label|includegraphics|bibliography|section|subsection|subsubsection|paragraph|subparagraph)\\*?(?:\\s*\\[[^]]*]){0,2}\\s*\\{([^{}]+)}"
                    + "|\\\\(?:begin|end)\\s*\\{(?:equation|equation\\*|align|align\\*|aligned|figure|figure\\*|table|table\\*|algorithm|algorithmic|enumerate|itemize|cases|split|gather|gather\\*|multline|multline\\*|IEEEeqnarray)\\}");

    private final PaperTaskRepository tasks;
    private final PaperSectionRepository sections;
    private final PaperTaskAnalysisRepository analyses;
    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final PaperStorageService storageService;
    private final LatexMaskingService maskingService;
    private final ObjectMapper objectMapper;

    public PaperSectionPolishService(PaperTaskRepository tasks,
                                     PaperSectionRepository sections,
                                     PaperTaskAnalysisRepository analyses,
                                     PaperPromptService promptService,
                                     PaperModelClient modelClient,
                                     PaperStorageService storageService,
                                     LatexMaskingService maskingService,
                                     ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.sections = sections;
        this.analyses = analyses;
        this.promptService = promptService;
        this.modelClient = modelClient;
        this.storageService = storageService;
        this.maskingService = maskingService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SectionPolishResult polishSection(Long taskId, LatexSection latexSection, String targetLanguage,
                                             double scoreThreshold, int maxAttempts) {
        return polishSection(taskId, latexSection, targetLanguage, normalizeScoreThreshold(scoreThreshold), maxAttempts, 1);
    }

    @Transactional
    public SectionPolishResult polishSection(Long taskId, LatexSection latexSection, String targetLanguage,
                                             int scoreThreshold, int maxRounds, int innerMaxAttempts) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        Optional<PaperSection> storedSection = sections.findByTaskIdOrderByOrderIndexAsc(taskId).stream()
                .filter(section -> section.getOrderIndex().equals(latexSection.orderIndex()))
                .findFirst();
        if (latexSection.role() == LatexSectionRole.REFERENCES) {
            return persist(task, storedSection, "SKIPPED", latexSection.rawText(), latexSection.rawText(), 0, false, 0,
                    reviewMeta("references_not_polished", "Reference sections are not polished.", 0, false, 0,
                            maxRounds, innerMaxAttempts, true, false, Map.of()), Map.of(), List.of());
        }

        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        String researchProfile = analysis == null || analysis.getResearchProfileJson() == null ? "{}" : analysis.getResearchProfileJson();
        String conceptLadder = analysis == null || analysis.getConceptLadderJson() == null ? "{}" : analysis.getConceptLadderJson();
        boolean introductionSection = latexSection.role() == LatexSectionRole.INTRO || (latexSection.title() != null && latexSection.title().toLowerCase().contains("intro"));
        MaskedLatexText masked = maskingService.mask(latexSection.rawText());
        String reviewComments = "";
        String lastRejectedCode = "polish_failed";
        String lastRejectedMessage = "Section polishing did not produce an acceptable candidate.";
        int rounds = Math.max(1, maxRounds);
        int repairsPerRound = Math.max(1, innerMaxAttempts);
        for (int round = 1; round <= rounds; round++) {
            String prompt = promptService.render(introductionSection ? "introduction-polish" : "section-polish", Map.of(
                    "targetLanguage", blankToDefault(targetLanguage, task.getTargetLanguage()),
                    "paperTitle", task.getTitle(),
                    "researchProfile", researchProfile,
                    "conceptLadder", conceptLadder,
                    "sectionTitle", latexSection.title(),
                    "attemptIndex", round,
                    "maxAttempts", rounds,
                    "reviewComments", reviewComments.isBlank() ? "None." : reviewComments,
                    "sectionText", masked.text()
            ));
            String polishResponse;
            try {
                polishResponse = modelClient.complete("Preserve placeholders exactly and return tagged output only.", prompt, 0.3, 4096);
            } catch (Exception ex) {
                return persist(task, storedSection, "MODEL_FAILED", latexSection.rawText(), latexSection.rawText(), 0, false, round,
                        reviewMeta("model_call_failed", rootMessage(ex), 0, false, round, rounds, repairsPerRound,
                                true, false, Map.of()), Map.of(), maskingService.lint(latexSection.rawText()));
            }
            String polishedMasked = extractOutput(polishResponse);
            PlaceholderValidation validation = maskingService.validatePlaceholders(polishedMasked, masked.placeholderSet());
            if (!validation.valid()) {
                lastRejectedCode = "placeholder_validation_failed";
                lastRejectedMessage = "Missing placeholders: " + validation.missing() + "; unexpected placeholders: " + validation.unexpected();
                reviewComments = lastRejectedMessage;
                continue;
            }
            String polished = maskingService.unmask(polishedMasked, masked.placeholders());
            CandidateValidation candidateValidation = validateCandidate(latexSection.rawText(), polished);
            if (!candidateValidation.valid()) {
                lastRejectedCode = candidateValidation.reasonCode();
                lastRejectedMessage = candidateValidation.reasonMessage();
                reviewComments = lastRejectedMessage;
                continue;
            }
            Map<String, Object> candidateDiff = diff(latexSection.rawText(), polished);
            Review review = review(task, latexSection, researchProfile, latexSection.rawText(), polished, targetLanguage, scoreThreshold, candidateDiff);
            if (review.passed() && review.score() >= scoreThreshold) {
                return persist(task, storedSection, "POLISHED", latexSection.rawText(), polished, review.score(), true, round,
                        reviewMeta("polished", "Section passed review.", review.score(), true, round, rounds,
                                repairsPerRound, false, false, review.raw()), candidateDiff, candidateValidation.lintIssues());
            }

            String criticComments = summarizeReview(review.raw());
            String finalReasonCode = review.reasonCode();
            String finalReasonMessage = review.reasonMessage();
            Map<String, Object> finalReviewRaw = review.raw();
            Map<String, Object> finalDiff = candidateDiff;
            CandidateValidation finalValidation = candidateValidation;
            String currentPolished = polished;

            for (int repairAttempt = 1; repairAttempt <= repairsPerRound; repairAttempt++) {
                RepairResult repair = repair(task, latexSection, researchProfile, latexSection.rawText(), currentPolished, targetLanguage, criticComments);
                if (!repair.success()) {
                    finalReasonCode = firstReason(repair.raw(), "repair_model_call_failed");
                    finalReasonMessage = firstMessage(repair.raw(), "Repair failed.");
                    criticComments = criticComments + "\nrepair_failed: " + repair.raw();
                    continue;
                }
                CandidateValidation repairValidation = validateCandidate(latexSection.rawText(), repair.polishedText());
                if (repairValidation.valid()) {
                    Map<String, Object> repairDiff = diff(latexSection.rawText(), repair.polishedText());
                    Review repairReview = review(task, latexSection, researchProfile, latexSection.rawText(), repair.polishedText(), targetLanguage, scoreThreshold, repairDiff);
                    Map<String, Object> repairReviewRaw = withRepairMeta(repairReview.raw(), review.raw(), true, repair.raw());
                    if (repairReview.passed() && repairReview.score() >= scoreThreshold) {
                        return persist(task, storedSection, "POLISHED", latexSection.rawText(), repair.polishedText(), repairReview.score(), true, round,
                                reviewMeta("polished", "Section passed review after repair.", repairReview.score(), true, round,
                                        rounds, repairsPerRound, false, false, repairReviewRaw),
                                repairDiff, repairValidation.lintIssues());
                    }
                    currentPolished = repair.polishedText();
                    finalDiff = repairDiff;
                    finalValidation = repairValidation;
                    finalReviewRaw = repairReviewRaw;
                    finalReasonCode = repairReview.reasonCode();
                    finalReasonMessage = repairReview.reasonMessage();
                    criticComments = summarizeReview(repairReviewRaw);
                } else {
                    finalReasonCode = repairValidation.reasonCode();
                    finalReasonMessage = repairValidation.reasonMessage();
                    criticComments = criticComments + "\nrepair_rejected: " + repairValidation.reasonMessage();
                }
            }

            reviewComments = criticComments;
            if (round == rounds) {
                return persist(task, storedSection, "REVIEW_FAILED", latexSection.rawText(), currentPolished, number(finalReviewRaw.get("score"), review.score()), false, round,
                        reviewMeta(finalReasonCode, finalReasonMessage, number(finalReviewRaw.get("score"), review.score()), false, round,
                                rounds, repairsPerRound, true, false, finalReviewRaw),
                        finalDiff, finalValidation.lintIssues());
            }
        }
        return persist(task, storedSection, "PROTECTION_REJECTED", latexSection.rawText(), latexSection.rawText(), 0, false, rounds,
                reviewMeta(lastRejectedCode, lastRejectedMessage, 0, false, rounds, rounds, repairsPerRound,
                        true, true, Map.of()), Map.of(), maskingService.lint(latexSection.rawText()));
    }

    private boolean structuralCommandsPreserved(String original, String polished) {
        return structuralCommands(original).equals(structuralCommands(polished));
    }

    private boolean unexpectedTranslation(String original, String polished) {
        if (original == null || polished == null) return false;
        double originalCjk = cjkRatio(original);
        double polishedCjk = cjkRatio(polished);
        // Most uploaded IEEE/TAES LaTeX papers are English. If the original section is essentially English,
        // reject outputs that translate substantial prose into Chinese because this breaks venue language.
        return originalCjk < 0.02 && polishedCjk > 0.08 && cjkCount(polished) > 40;
    }

    private double cjkRatio(String text) {
        int letters = 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetter(ch)) letters++;
            if (isCjk(ch)) cjk++;
        }
        return letters == 0 ? 0 : (double) cjk / letters;
    }

    private int cjkCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (isCjk(text.charAt(i))) count++;
        return count;
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private List<String> structuralCommands(String text) {
        List<String> commands = new ArrayList<>();
        Matcher matcher = STRUCTURAL_COMMAND.matcher(text == null ? "" : text);
        while (matcher.find()) {
            commands.add(matcher.group().replaceAll("\\s+", " ").trim());
        }
        return commands;
    }

    private CandidateValidation validateCandidate(String original, String polished) {
        if (unexpectedTranslation(original, polished)) {
            return new CandidateValidation(false, "unexpected_translation_detected",
                    "The candidate appears to translate the section instead of polishing it.", List.of());
        }
        if (!structuralCommandsPreserved(original, polished)) {
            return new CandidateValidation(false, "structural_command_changed",
                    "The candidate changed protected LaTeX structure, labels, refs, cites, or environments.", List.of());
        }
        List<LatexLintIssue> lintIssues = maskingService.lint(polished);
        if (hasBlocker(lintIssues)) {
            return new CandidateValidation(false, "latex_lint_failed", "LaTeX lint blockers were found: " + lintIssues, lintIssues);
        }
        return new CandidateValidation(true, "", "", lintIssues);
    }

    private Review review(PaperTask task, LatexSection section, String researchProfile, String originalText,
                          String polishedText, String targetLanguage, double scoreThreshold, Map<String, Object> diffSummary) {
        String prompt = promptService.render("section-review", Map.of(
                "targetLanguage", blankToDefault(targetLanguage, task.getTargetLanguage()),
                "paperTitle", task.getTitle(),
                "researchProfile", researchProfile,
                "sectionTitle", section.title(),
                "scoreThreshold", scoreThreshold,
                "diffSummary", toJson(diffSummary),
                "originalText", originalText,
                "sectionText", polishedText
        ));
        String text;
        try {
            text = modelClient.complete("You return strict JSON only.", prompt, 0.1, 2048);
        } catch (Exception ex) {
            return new Review(0, false,
                    Map.of("reasonCode", "review_model_call_failed", "reasonMessage", rootMessage(ex)),
                    "review_model_call_failed",
                    rootMessage(ex));
        }
        Map<String, Object> raw = parseMap(text);
        double score = number(raw.get("score"), 0);
        if (score > 0 && score <= 1) {
            Map<String, Object> invalidRaw = new LinkedHashMap<>(raw);
            invalidRaw.put("reasonCode", "review_score_scale_invalid");
            invalidRaw.put("reasonMessage", "Review score must use the 0-100 scale, not a 0-1 ratio.");
            return new Review(score, false, invalidRaw, "review_score_scale_invalid",
                    "Review score must use the 0-100 scale, not a 0-1 ratio.");
        }
        if (score < 0 || score > 100) {
            Map<String, Object> invalidRaw = new LinkedHashMap<>(raw);
            invalidRaw.put("reasonCode", "review_score_out_of_range");
            invalidRaw.put("reasonMessage", "Review score must be between 0 and 100.");
            return new Review(score, false, invalidRaw, "review_score_out_of_range",
                    "Review score must be between 0 and 100.");
        }
        boolean passed = booleanValue(raw.get("passed"), score >= scoreThreshold);
        boolean introducedUnsupported = booleanValue(raw.get("introducedUnsupportedContent"), false);
        boolean needsRepair = booleanValue(raw.get("needsRepair"), false);
        if (introducedUnsupported || needsRepair) {
            passed = false;
        }
        if (score < scoreThreshold) {
            passed = false;
        }
        String reasonCode = passed ? "polished" : "review_failed";
        String reasonMessage = passed ? "Section passed review." : "Section review did not meet the configured threshold.";
        return new Review(score, passed, raw, reasonCode, reasonMessage);
    }

    private RepairResult repair(PaperTask task, LatexSection section, String researchProfile, String originalText,
                                String polishedText, String targetLanguage, String reviewComments) {
        String prompt = promptService.render("section-repair", Map.of(
                "targetLanguage", blankToDefault(targetLanguage, task.getTargetLanguage()),
                "paperTitle", task.getTitle(),
                "researchProfile", researchProfile,
                "sectionTitle", section.title(),
                "reviewComments", reviewComments == null || reviewComments.isBlank() ? "None." : reviewComments,
                "originalText", originalText,
                "polishedText", polishedText
        ));
        String text;
        try {
            text = modelClient.complete("Repair conservatively and return tagged output only.", prompt, 0.2, 4096);
        } catch (Exception ex) {
            return new RepairResult(false, polishedText, Map.of("reason", "repair_model_call_failed", "message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
        String repaired = extractOutput(text);
        if (repaired.isBlank()) {
            return new RepairResult(false, polishedText, Map.of("reason", "repair_empty_output"));
        }
        return new RepairResult(true, repaired, Map.of("reason", "repair_model_output"));
    }

    private Map<String, Object> withRepairMeta(Map<String, Object> repairReview, Map<String, Object> initialReview,
                                               boolean repairApplied, Map<String, Object> repairRaw) {
        Map<String, Object> merged = new LinkedHashMap<>(repairReview == null ? Map.of() : repairReview);
        merged.put("repairApplied", repairApplied);
        merged.put("initialReview", initialReview == null ? Map.of() : initialReview);
        merged.put("repair", repairRaw == null ? Map.of() : repairRaw);
        return merged;
    }

    private SectionPolishResult persist(PaperTask task, Optional<PaperSection> storedSection, String status,
                                        String original, String polished, double score, boolean passed,
                                        int attempts, Map<String, Object> review, Map<String, Object> diff,
                                        List<LatexLintIssue> lintIssues) {
        Long id = null;
        if (storedSection.isPresent()) {
            PaperSection section = storedSection.get();
            id = section.getId();
            section.setPolishStatus(status);
            String originalObjectKey = storageService.storeArtifact(task.getUserId(), "section_original", "section-" + section.getOrderIndex() + "-original.tex", original.getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/x-tex; charset=UTF-8");
            String polishedObjectKey = storageService.storeArtifact(task.getUserId(), "section_polished", "section-" + section.getOrderIndex() + "-polished.tex", polished.getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/x-tex; charset=UTF-8");
            section.setOriginalObjectKey(originalObjectKey);
            section.setPolishedObjectKey(polishedObjectKey);
            Map<String, Object> reviewWithMeta = new LinkedHashMap<>(review);
            reviewWithMeta.put("score", score);
            reviewWithMeta.put("passed", passed);
            reviewWithMeta.put("attempts", attempts);
            reviewWithMeta.put("lintIssues", lintIssues);
            reviewWithMeta.putIfAbsent("keptOriginal", !"POLISHED".equals(status));
            reviewWithMeta.putIfAbsent("protectionTriggered", "PROTECTION_REJECTED".equals(status));
            section.setReviewJson(toJson(reviewWithMeta));
            section.setDiffJson(toJson(diff));
            sections.save(section);
        }
        return new SectionPolishResult(id, status, original, polished, score, passed, attempts, review, diff, lintIssues);
    }

    private String extractOutput(String text) {
        if (text == null) return "";
        Matcher matcher = OUTPUT_TAG.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text.trim();
    }

    private boolean hasBlocker(List<LatexLintIssue> issues) {
        return issues.stream().anyMatch(issue -> issue.severity() == LatexLintIssue.Severity.BLOCKER);
    }

    private Map<String, Object> parseMap(String text) {
        String json = extractJsonObject(text == null ? "" : text);
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("score", 0, "passed", false, "issues", List.of(Map.of("severity", "blocker", "message", "review_json_parse_failed")), "rawText", text == null ? "" : text);
        }
    }

    private String extractJsonObject(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    private Map<String, Object> diff(String original, String polished) {
        List<String> originalWords = words(original);
        List<String> polishedWords = words(polished);
        int commonPrefix = 0;
        while (commonPrefix < originalWords.size() && commonPrefix < polishedWords.size()
                && originalWords.get(commonPrefix).equals(polishedWords.get(commonPrefix))) {
            commonPrefix++;
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("originalWordCount", originalWords.size());
        diff.put("polishedWordCount", polishedWords.size());
        diff.put("changed", !original.equals(polished));
        diff.put("commonPrefixWords", commonPrefix);
        diff.put("lengthDelta", polished.length() - original.length());
        int maxWords = Math.max(originalWords.size(), polishedWords.size());
        diff.put("changeRatio", maxWords == 0 ? 0.0 : 1.0 - ((double) commonPrefix / maxWords));
        return diff;
    }

    private List<String> words(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) return List.of();
        return new ArrayList<>(List.of(normalized.split(" ")));
    }

    private String summarizeReview(Map<String, Object> review) {
        try {
            JsonNode node = objectMapper.valueToTree(review);
            List<String> messages = new ArrayList<>();
            JsonNode issues = node.path("issues");
            if (issues.isArray()) {
                issues.forEach(issue -> messages.add(issue.path("severity").asText("issue") + ": " + issue.path("message").asText("")));
            }
            JsonNode suggestions = node.path("suggestions");
            if (suggestions.isArray()) {
                suggestions.forEach(suggestion -> messages.add("suggestion: " + suggestion.asText("")));
            }
            return messages.isEmpty() ? toJson(review) : String.join("\n", messages);
        } catch (Exception ex) {
            return toJson(review);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    private int normalizeScoreThreshold(double scoreThreshold) {
        return (int) Math.max(0, Math.min(100, Math.round(scoreThreshold)));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private Map<String, Object> reviewMeta(String reasonCode,
                                           String reasonMessage,
                                           double score,
                                           boolean passed,
                                           int attempts,
                                           int maxRounds,
                                           int innerMaxAttempts,
                                           boolean keptOriginal,
                                           boolean protectionTriggered,
                                           Map<String, Object> raw) {
        Map<String, Object> meta = new LinkedHashMap<>(raw == null ? Map.of() : raw);
        meta.put("reasonCode", reasonCode);
        meta.put("reasonMessage", reasonMessage);
        meta.put("score", score);
        meta.put("passed", passed);
        meta.put("attempts", attempts);
        meta.put("maxRounds", maxRounds);
        meta.put("innerMaxAttempts", innerMaxAttempts);
        meta.put("keptOriginal", keptOriginal);
        meta.put("protectionTriggered", protectionTriggered);
        return meta;
    }

    private String firstReason(Map<String, Object> raw, String fallback) {
        Object reasonCode = raw == null ? null : raw.get("reasonCode");
        if (reasonCode == null) {
            reasonCode = raw == null ? null : raw.get("reason");
        }
        return reasonCode == null || String.valueOf(reasonCode).isBlank() ? fallback : String.valueOf(reasonCode);
    }

    private String firstMessage(Map<String, Object> raw, String fallback) {
        Object message = raw == null ? null : raw.get("reasonMessage");
        if (message == null) {
            message = raw == null ? null : raw.get("message");
        }
        return message == null || String.valueOf(message).isBlank() ? fallback : String.valueOf(message);
    }

    private record CandidateValidation(boolean valid, String reasonCode, String reasonMessage, List<LatexLintIssue> lintIssues) {
    }

    private record RepairResult(boolean success, String polishedText, Map<String, Object> raw) {
    }

    private record Review(double score, boolean passed, Map<String, Object> raw, String reasonCode, String reasonMessage) {
    }
}
