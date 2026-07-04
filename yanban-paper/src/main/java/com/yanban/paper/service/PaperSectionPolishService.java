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
        PaperTask task = tasks.findById(taskId).orElseThrow();
        Optional<PaperSection> storedSection = sections.findByTaskIdOrderByOrderIndexAsc(taskId).stream()
                .filter(section -> section.getOrderIndex().equals(latexSection.orderIndex()))
                .findFirst();
        if (latexSection.role() == LatexSectionRole.REFERENCES) {
            return persist(task, storedSection, "SKIPPED", latexSection.rawText(), latexSection.rawText(), 0, false, 0,
                    Map.of("reason", "references_not_polished"), Map.of(), List.of());
        }

        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        String researchProfile = analysis == null || analysis.getResearchProfileJson() == null ? "{}" : analysis.getResearchProfileJson();
        String conceptLadder = analysis == null || analysis.getConceptLadderJson() == null ? "{}" : analysis.getConceptLadderJson();
        boolean introductionSection = latexSection.role() == LatexSectionRole.INTRO || (latexSection.title() != null && latexSection.title().toLowerCase().contains("intro"));
        MaskedLatexText masked = maskingService.mask(latexSection.rawText());
        String reviewComments = "";
        String lastRejectedReason = "";
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String prompt = promptService.render(introductionSection ? "introduction-polish" : "section-polish", Map.of(
                    "targetLanguage", blankToDefault(targetLanguage, task.getTargetLanguage()),
                    "paperTitle", task.getTitle(),
                    "researchProfile", researchProfile,
                    "conceptLadder", conceptLadder,
                    "sectionTitle", latexSection.title(),
                    "attemptIndex", attempt,
                    "maxAttempts", attempts,
                    "reviewComments", reviewComments.isBlank() ? "None." : reviewComments,
                    "sectionText", masked.text()
            ));
            String polishResponse;
            try {
                polishResponse = modelClient.complete("Preserve placeholders exactly and return tagged output only.", prompt, 0.3, 4096);
            } catch (Exception ex) {
                return persist(task, storedSection, "FAILED_KEEP_ORIGINAL", latexSection.rawText(), latexSection.rawText(), 0, false, attempt,
                        Map.of("reason", "model_call_failed", "message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()), Map.of(), maskingService.lint(latexSection.rawText()));
            }
            String polishedMasked = extractOutput(polishResponse);
            PlaceholderValidation validation = maskingService.validatePlaceholders(polishedMasked, masked.placeholderSet());
            if (!validation.valid()) {
                lastRejectedReason = "placeholder_validation_failed missing=" + validation.missing() + " unexpected=" + validation.unexpected();
                reviewComments = lastRejectedReason;
                continue;
            }
            String polished = maskingService.unmask(polishedMasked, masked.placeholders());
            CandidateValidation candidateValidation = validateCandidate(latexSection.rawText(), polished);
            if (!candidateValidation.valid()) {
                lastRejectedReason = candidateValidation.reason();
                reviewComments = lastRejectedReason;
                continue;
            }
            Map<String, Object> candidateDiff = diff(latexSection.rawText(), polished);
            Review review = review(task, latexSection, researchProfile, latexSection.rawText(), polished, targetLanguage, scoreThreshold, candidateDiff);
            if (review.passed() && review.score() >= scoreThreshold) {
                return persist(task, storedSection, "POLISHED", latexSection.rawText(), polished, review.score(), true, attempt,
                        review.raw(), candidateDiff, candidateValidation.lintIssues());
            }

            String criticComments = summarizeReview(review.raw());
            RepairResult repair = repair(task, latexSection, researchProfile, latexSection.rawText(), polished, targetLanguage, criticComments);
            if (repair.success()) {
                CandidateValidation repairValidation = validateCandidate(latexSection.rawText(), repair.polishedText());
                if (repairValidation.valid()) {
                    Map<String, Object> repairDiff = diff(latexSection.rawText(), repair.polishedText());
                    Review repairReview = review(task, latexSection, researchProfile, latexSection.rawText(), repair.polishedText(), targetLanguage, scoreThreshold, repairDiff);
                    Map<String, Object> repairReviewRaw = withRepairMeta(repairReview.raw(), review.raw(), true, repair.raw());
                    if (repairReview.passed() && repairReview.score() >= scoreThreshold) {
                        return persist(task, storedSection, "POLISHED", latexSection.rawText(), repair.polishedText(), repairReview.score(), true, attempt,
                                repairReviewRaw, repairDiff, repairValidation.lintIssues());
                    }
                    polished = repair.polishedText();
                    candidateDiff = repairDiff;
                    candidateValidation = repairValidation;
                    review = new Review(repairReview.score(), repairReview.passed(), repairReviewRaw);
                    criticComments = summarizeReview(repairReviewRaw);
                } else {
                    criticComments = criticComments + "\nrepair_rejected: " + repairValidation.reason();
                }
            } else {
                criticComments = criticComments + "\nrepair_failed: " + repair.raw();
            }

            reviewComments = criticComments;
            if (attempt == attempts) {
                return persist(task, storedSection, "REVIEW_FAILED", latexSection.rawText(), polished, review.score(), false, attempt,
                        review.raw(), candidateDiff, candidateValidation.lintIssues());
            }
        }
        return persist(task, storedSection, "FAILED_KEEP_ORIGINAL", latexSection.rawText(), latexSection.rawText(), 0, false, attempts,
                Map.of("reason", lastRejectedReason.isBlank() ? "polish_failed" : lastRejectedReason), Map.of(), maskingService.lint(latexSection.rawText()));
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
            return new CandidateValidation(false, "unexpected_translation_detected", List.of());
        }
        if (!structuralCommandsPreserved(original, polished)) {
            return new CandidateValidation(false, "structural_command_changed", List.of());
        }
        List<LatexLintIssue> lintIssues = maskingService.lint(polished);
        if (hasBlocker(lintIssues)) {
            return new CandidateValidation(false, "latex_lint_failed " + lintIssues, lintIssues);
        }
        return new CandidateValidation(true, "", lintIssues);
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
            return new Review(0, false, Map.of("reason", "review_model_call_failed", "message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
        Map<String, Object> raw = parseMap(text);
        double score = number(raw.get("score"), 0);
        boolean passed = booleanValue(raw.get("passed"), score >= scoreThreshold);
        boolean introducedUnsupported = booleanValue(raw.get("introducedUnsupportedContent"), false);
        boolean needsRepair = booleanValue(raw.get("needsRepair"), false);
        if (introducedUnsupported || needsRepair) {
            passed = false;
        }
        return new Review(score, passed, raw);
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

    private record CandidateValidation(boolean valid, String reason, List<LatexLintIssue> lintIssues) {
    }

    private record RepairResult(boolean success, String polishedText, Map<String, Object> raw) {
    }

    private record Review(double score, boolean passed, Map<String, Object> raw) {
    }
}
