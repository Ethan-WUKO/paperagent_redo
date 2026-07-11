package com.yanban.paper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexSection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One bounded, report-only audit over the section outputs. */
@Service
public class PaperGlobalReviewService {

    private static final List<String> ALLOWED_TYPES = List.of(
            "TRANSITION", "LOGIC", "NOTATION", "FORMULA", "CLAIM_RESULT_MISMATCH", "DUPLICATION");
    private static final Set<String> ALLOWED_NOTATION_RULES = Set.of("DIMENSION_CONFLICT", "SYMBOL_CONFLICT", "TERM_CONFLICT");
    private static final Set<String> ALLOWED_FORMULA_RULES = Set.of(
            "DIMENSION_CONFLICT", "FORMULA_PROSE_MISMATCH", "UNDEFINED_SYMBOL", "EQUATION_REFERENCE_MISMATCH");
    private static final Pattern EQUATION_BLOCK = Pattern.compile(
            "(?s)\\\\begin\\{(equation\\*?|align\\*?|gather\\*?|multline\\*?)\\}.*?\\\\end\\{\\1\\}");
    private static final Pattern MATRIX_DECLARATION = Pattern.compile(
            "(?s)\\\\mathbf\\{([^{}]+)}.{0,320}?\\\\in\\s*\\\\mathbb\\{C}\\s*\\^\\s*\\{\\s*([^{}\\s]+)\\s*\\\\times\\s*([^{}\\s]+)\\s*}");
    private static final Pattern GRAM_PRODUCT = Pattern.compile(
            "\\\\mathbf\\{([^{}]+)}(?:_\\{?[^\\s=]+}?)?\\s*=\\s*\\\\mathbf\\{([^{}]+)}(?:_\\{?[^\\s^=]+}?)?\\s*\\^\\s*(?:\\{\\s*H\\s*}|H)\\s*\\\\mathbf\\{([^{}]+)}(?:_\\{?[^\\s=]+}?)?");

    private final PaperTaskAnalysisRepository analyses;
    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final ObjectMapper objectMapper;

    public PaperGlobalReviewService(PaperTaskAnalysisRepository analyses,
                                    PaperPromptService promptService,
                                    PaperModelClient modelClient,
                                    ObjectMapper objectMapper) {
        this.analyses = analyses;
        this.promptService = promptService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> reviewAndSave(PaperTask task,
                                             LatexDocument document,
                                             String finalDraftTex,
                                             List<PaperSection> storedSections) {
        PaperTaskAnalysis analysis = analyses.findByTaskId(task.getId()).orElseGet(() -> new PaperTaskAnalysis(task.getId()));
        String researchProfile = blankToDefault(analysis.getResearchProfileJson(), "{}");
        String conceptLadder = blankToDefault(analysis.getConceptLadderJson(), "{}");
        String gapMatrix = blankToDefault(analysis.getGapMatrixJson(), "{}");
        String prompt = promptService.render("global-review", Map.of(
                "targetLanguage", blankToDefault(task.getTargetLanguage(), "en"),
                "paperTitle", blankToDefault(task.getTitle(), "Untitled paper"),
                "researchProfile", researchProfile,
                "conceptLadder", conceptLadder,
                "gapMatrix", gapMatrix,
                "sectionDossier", buildDossier(document, storedSections),
                "technicalFacts", buildTechnicalFacts(document)
        ));
        Map<String, Object> result;
        try {
            result = normalize(
                    readMap(modelClient.complete("You return strict JSON only. Every issue must cite exact manuscript evidence. Do not rewrite formulas.", prompt, 0.1, 4096)),
                    finalDraftTex,
                    document.sections().stream().map(LatexSection::orderIndex).collect(java.util.stream.Collectors.toSet()));
        } catch (Exception ex) {
            result = fallback(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        result.put("generatedBy", "paper-global-review-v2");
        result.put("formulaPolicy", "FORMULA issues are report-only and never auto-fixed.");
        Map<String, Object> matrix = new LinkedHashMap<>(readMap(gapMatrix));
        matrix.put("globalReview", result);
        analysis.setGapMatrixJson(toJson(matrix));
        analyses.save(analysis);
        return result;
    }

    private String buildDossier(LatexDocument document, List<PaperSection> storedSections) {
        StringBuilder dossier = new StringBuilder();
        for (LatexSection section : document.sections()) {
            PaperSection stored = storedSections.stream().filter(item -> item.getOrderIndex().equals(section.orderIndex())).findFirst().orElse(null);
            dossier.append("SECTION ").append(section.orderIndex()).append(" | title=").append(section.title())
                    .append(" | role=").append(section.role()).append("\n")
                    .append(compactExcerpt(section.rawText(), 1200, 700)).append("\n\n");
            if (stored != null && stored.getReviewJson() != null) {
                dossier.append("SECTION_REVIEW=").append(truncate(stored.getReviewJson(), 700)).append("\n\n");
            }
            if (dossier.length() > 18000) break;
        }
        return dossier.toString();
    }

    private String buildTechnicalFacts(LatexDocument document) {
        StringBuilder facts = new StringBuilder();
        for (LatexSection section : document.sections()) {
            String raw = section.rawText() == null ? "" : section.rawText();
            Matcher equations = EQUATION_BLOCK.matcher(raw);
            while (equations.find() && facts.length() < 22000) {
                String equation = equations.group();
                if (equation.contains("\\label{") || equation.contains("\\mathbb{C}") || equation.contains("\\mathbf{")) {
                    facts.append("SECTION ").append(section.orderIndex()).append(" EQUATION:\n")
                            .append(equation).append("\n\n");
                }
            }
            for (String line : raw.split("\\R")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if ((line.contains("\\mathbb{C}") || line.contains("\\label{")
                        || lower.contains(" is defined as") || lower.contains(" denote"))
                        && facts.length() < 22000) {
                    facts.append("SECTION ").append(section.orderIndex()).append(" FACT: ").append(line.trim()).append('\n');
                }
            }
            if (facts.length() >= 22000) break;
        }
        return facts.toString();
    }

    private Map<String, Object> normalize(Map<String, Object> raw, String finalDraftTex, Set<Integer> knownSectionOrders) {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> suppressed = new ArrayList<>();
        Object candidate = raw.get("issues");
        if (candidate instanceof List<?> values) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> map)) continue;
                String type = string(map.get("type")).toUpperCase(Locale.ROOT);
                type = ALLOWED_TYPES.contains(type) ? type : "LOGIC";
                String ruleId = string(map.get("ruleId")).toUpperCase(Locale.ROOT);
                List<Map<String, Object>> evidence = verifiedEvidence(map.get("evidence"), finalDraftTex, knownSectionOrders);
                String suppressionReason = suppressionReason(type, ruleId, string(map.get("message")), evidence);
                if (evidence.isEmpty()) suppressionReason = "NO_VERIFIABLE_EVIDENCE";
                if (!suppressionReason.isBlank()) {
                    suppressed.add(Map.of("type", type, "ruleId", ruleId, "reason", suppressionReason));
                    continue;
                }
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("type", type);
                issue.put("ruleId", ruleId);
                issue.put("sectionIds", map.containsKey("sectionIds") ? map.get("sectionIds") : List.of());
                issue.put("severity", normalizeSeverity(string(map.get("severity"))));
                issue.put("message", stringOr(map.get("message"), "Global consistency issue."));
                issue.put("suggestedFix", string(map.get("suggestedFix")));
                issue.put("autoFixAllowed", !"FORMULA".equals(type) && map.get("autoFixAllowed") instanceof Boolean b && b);
                issue.put("evidence", evidence);
                issue.put("verified", true);
                issues.add(issue);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issues", issues);
        result.put("issueCount", issues.size());
        result.put("suppressedIssueCount", suppressed.size());
        result.put("suppressedIssues", suppressed);
        return result;
    }

    private List<Map<String, Object>> verifiedEvidence(Object value, String finalDraftTex, Set<Integer> knownSectionOrders) {
        if (!(value instanceof List<?> values)) return List.of();
        String normalizedDraft = normalizeWhitespace(finalDraftTex);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String quote = string(map.get("quote"));
            if (quote.isBlank() || !normalizedDraft.contains(normalizeWhitespace(quote))) continue;
            Integer sectionOrder = integer(map.get("sectionOrder"));
            if (sectionOrder != null && !knownSectionOrders.contains(sectionOrder)) continue;
            String equationLabel = string(map.get("equationLabel"));
            if (!equationLabel.isBlank() && !finalDraftTex.contains("\\label{" + equationLabel + "}")) continue;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("sectionOrder", sectionOrder);
            evidence.put("equationLabel", equationLabel);
            evidence.put("quote", quote);
            result.add(evidence);
        }
        return result;
    }

    private String suppressionReason(String type, String ruleId, String message, List<Map<String, Object>> evidence) {
        if ("NOTATION".equals(type) && !ALLOWED_NOTATION_RULES.contains(ruleId)) return "UNSUPPORTED_NOTATION_RULE";
        if ("FORMULA".equals(type) && !ALLOWED_FORMULA_RULES.contains(ruleId)) return "UNSUPPORTED_FORMULA_RULE";
        String lower = message.toLowerCase(Locale.ROOT);
        if ("FORMULA".equals(type) && (lower.contains("stray period") || lower.contains("equation-ending punctuation")
                || lower.contains("period appears before") || lower.contains("punctuation before"))) {
            return "EQUATION_PUNCTUATION_IS_VALID";
        }
        if ("DIMENSION_CONFLICT".equals(ruleId) && !hasDimensionConflict(evidence)) return "DIMENSION_CONFLICT_NOT_PROVEN";
        return "";
    }

    private boolean hasDimensionConflict(List<Map<String, Object>> evidence) {
        String joined = evidence.stream().map(item -> string(item.get("quote"))).collect(java.util.stream.Collectors.joining("\n"));
        Map<String, Set<Dimension>> dimensions = new LinkedHashMap<>();
        Matcher declarations = MATRIX_DECLARATION.matcher(joined);
        while (declarations.find()) {
            dimensions.computeIfAbsent(declarations.group(1), ignored -> new LinkedHashSet<>())
                    .add(new Dimension(cleanDimension(declarations.group(2)), cleanDimension(declarations.group(3))));
        }
        if (dimensions.values().stream().anyMatch(values -> values.size() > 1)) return true;
        Matcher products = GRAM_PRODUCT.matcher(joined);
        while (products.find()) {
            if (!products.group(2).equals(products.group(3))) continue;
            Dimension input = singleDimension(dimensions.get(products.group(2)));
            Dimension output = singleDimension(dimensions.get(products.group(1)));
            if (input != null && output != null && !output.equals(new Dimension(input.columns(), input.columns()))) return true;
        }
        return false;
    }

    private Dimension singleDimension(Set<Dimension> values) {
        return values == null || values.size() != 1 ? null : values.iterator().next();
    }

    private String cleanDimension(String value) {
        return value == null ? "" : value.replaceAll("[{}\\s]", "");
    }

    private Map<String, Object> fallback(String message) {
        return new LinkedHashMap<>(Map.of(
                "issues", List.of(),
                "issueCount", 0,
                "warning", "Global review unavailable: " + truncate(message, 300),
                "degraded", true));
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
        try { return objectMapper.writeValueAsString(value); } catch (Exception ignored) { return "{}"; }
    }
    private String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String stringOr(Object value, String fallback) { String text = string(value); return text.isBlank() ? fallback : text; }
    private String normalizeSeverity(String value) { return List.of("minor", "major", "blocker").contains(value.toLowerCase(Locale.ROOT)) ? value.toLowerCase(Locale.ROOT) : "minor"; }
    private String blankToDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String truncate(String value, int max) { if (value == null) return ""; String normalized = value.replaceAll("\\s+", " ").trim(); return normalized.length() <= max ? normalized : normalized.substring(0, max) + " ..."; }
    private String compactExcerpt(String value, int head, int tail) { if (value == null) return ""; if (value.length() <= head + tail) return value; return value.substring(0, head) + "\n...\n" + value.substring(value.length() - tail); }
    private String normalizeWhitespace(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }
    private Integer integer(Object value) { if (value instanceof Number number) return number.intValue(); try { return value == null ? null : Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; } }
    private record Dimension(String rows, String columns) {}
}
