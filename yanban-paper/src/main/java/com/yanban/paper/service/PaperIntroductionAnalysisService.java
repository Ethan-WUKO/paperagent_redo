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
import com.yanban.paper.latex.LatexSectionRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperIntroductionAnalysisService {

    private final PaperTaskRepository tasks;
    private final PaperTaskAnalysisRepository analyses;
    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final ObjectMapper objectMapper;

    public PaperIntroductionAnalysisService(PaperTaskRepository tasks,
                                            PaperTaskAnalysisRepository analyses,
                                            PaperPromptService promptService,
                                            PaperModelClient modelClient,
                                            ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.analyses = analyses;
        this.promptService = promptService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> analyzeAndSave(Long taskId, LatexDocument document, String targetLanguage) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElseGet(() -> new PaperTaskAnalysis(taskId));
        String introduction = introductionText(document);
        String prompt = promptService.render("introduction-analysis", Map.of(
                "targetLanguage", blankToDefault(targetLanguage, task.getTargetLanguage()),
                "paperTitle", blankToDefault(document.title(), task.getTitle()),
                "researchProfile", blankToDefault(analysis.getResearchProfileJson(), "{}"),
                "paperOverview", paperOverview(document),
                "introductionText", truncate(introduction == null ? "" : introduction, 12000)
        ));
        Map<String, Object> result;
        try {
            result = analyzeWithMultipleCalls(task, document, introduction, prompt);
        } catch (Exception ex) {
            result = fallback(task, document, introduction, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        analysis.setConceptLadderJson(toJson(result));
        analyses.save(analysis);
        return result;
    }

    private Map<String, Object> analyzeWithMultipleCalls(PaperTask task, LatexDocument document, String introduction, String prompt) throws Exception {
        Map<String, Object> combined = new LinkedHashMap<>();
        Map<String, Object> callDiagnostics = new LinkedHashMap<>();
        StringBuilder raw = new StringBuilder();

        String planText = callIntroductionAnalysisPart(prompt, planOnlyInstruction(), 4096);
        raw.append("PLAN_CALL:\n").append(planText).append("\n\n");
        Map<String, Object> planMap = readMap(planText);
        combined.put("introductionPlan", planMap.getOrDefault("introductionPlan", planMap));
        callDiagnostics.put("planCallChars", planText.length());

        String slotsText = callIntroductionAnalysisPart(prompt, slotsOnlyInstruction(), 8192);
        raw.append("SLOTS_CALL:\n").append(slotsText).append("\n\n");
        Map<String, Object> slotsMap = readMap(slotsText);
        Object slots = firstNonNull(slotsMap.get("citationSlots"), slotsMap.get("slots"), slotsMap.get("citation_slots"));
        if (!(slots instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("slots API call returned no citationSlots array; raw=" + truncate(slotsText, 800));
        }
        combined.put("citationSlots", slots);
        callDiagnostics.put("slotsCallChars", slotsText.length());
        callDiagnostics.put("slotsCallRawCount", list.size());

        try {
            String auditText = callIntroductionAnalysisPart(prompt, auditOnlyInstruction(), 4096);
            raw.append("AUDIT_CALL:\n").append(auditText).append("\n");
            Map<String, Object> auditMap = readMap(auditText);
            combined.put("citationAudit", auditMap.getOrDefault("citationAudit", List.of()));
            callDiagnostics.put("auditCallChars", auditText.length());
        } catch (Exception auditEx) {
            combined.put("citationAudit", List.of(Map.of("status", "UNKNOWN", "reason", "audit call failed: " + auditEx.getMessage())));
            callDiagnostics.put("auditCallError", auditEx.getMessage() == null ? auditEx.getClass().getSimpleName() : auditEx.getMessage());
        }

        Map<String, Object> normalized = normalize(combined, task, document, introduction, false, raw.toString());
        Map<String, Object> diagnostics = diagnosticsMap(normalized);
        diagnostics.put("llmCallMode", "multi-call-plan-slots-audit-v1");
        diagnostics.put("llmCallCount", 3);
        diagnostics.putAll(callDiagnostics);
        normalized.put("introductionAnalysisDiagnostics", diagnostics);
        return normalized;
    }

    private String callIntroductionAnalysisPart(String prompt, String instruction, int maxTokens) {
        return modelClient.complete("You return strict JSON only and never invent citations.", prompt + instruction, 0.15, maxTokens);
    }

    private String planOnlyInstruction() {
        return "\n\nSUBTASK FOR THIS API CALL: Return ONLY this JSON object: {\"introductionPlan\":{...}}. Do not return citationSlots or citationAudit. Keep each paragraph item concise.";
    }

    private String slotsOnlyInstruction() {
        return "\n\nSUBTASK FOR THIS API CALL: Return ONLY this JSON object: {\"citationSlots\":[...]}. Generate 8 to 14 granular slots. Each slot must include id, category, claim, citationNeed, existingCitationKeys, coreTerms, and 2-4 short search queries. Do not return introductionPlan or citationAudit.";
    }

    private String auditOnlyInstruction() {
        return "\n\nSUBTASK FOR THIS API CALL: Return ONLY this JSON object: {\"citationAudit\":[...]}. Keep at most 16 audit items. Keep reasons short. Do not return introductionPlan or citationSlots.";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> diagnosticsMap(Map<String, Object> normalized) {
        Object existing = normalized.get("introductionAnalysisDiagnostics");
        if (existing instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> raw, PaperTask task, LatexDocument document, String introduction, boolean degraded, String rawText) {
        List<Map<String, Object>> slots = new ArrayList<>();
        Object rawSlots = firstNonNull(raw.get("citationSlots"), raw.get("slots"), raw.get("citation_slots"));
        int rawSlotCount = rawSlots instanceof List<?> rawList ? rawList.size() : 0;
        int acceptedLlmSlotCount = 0;
        if (rawSlots instanceof List<?> list) {
            int index = 1;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                String claim = string(map.get("claim"));
                if (claim.isBlank()) continue;
                List<String> queries = strings(firstNonNull(map.get("queries"), map.get("searchQueries"), map.get("search_queries")));
                if (queries.isEmpty()) queries = defaultQueries(claim);
                List<String> coreTerms = strings(firstNonNull(map.get("coreTerms"), map.get("core_terms"), map.get("keywords")));
                slots.add(slot("slot-" + index++, stringOr(map.get("category"), "IntroductionClaim"), claim,
                        stringOr(map.get("citationNeed"), "NEEDS_SUPPORT"), queries, strings(map.get("existingCitationKeys")), coreTerms));
                acceptedLlmSlotCount++;
            }
        }
        int minimumSlots = minimumSlotCount(introduction);
        boolean usedFallbackSlots = slots.isEmpty();
        int beforeFallbackSlotCount = slots.size();
        String fallbackReason = "";
        if (usedFallbackSlots) {
            fallbackReason = rawSlotCount == 0 ? "LLM returned no citationSlots array" : "LLM citationSlots contained no usable slots with claim";
            slots = fallbackSlots(task, document, introduction);
        } else if (slots.size() < minimumSlots && slots.size() < 10) {
            fallbackReason = "LLM returned fewer usable slots than minimumSlotCount";
            mergeFallbackSlots(slots, fallbackSlots(task, document, introduction), minimumSlots);
            usedFallbackSlots = true;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedBy", degraded || usedFallbackSlots ? "introduction-analysis-fallback-v2" : "introduction-analysis-v1");
        result.put("degraded", degraded || usedFallbackSlots);
        result.put("paperTitle", blankToDefault(document.title(), task.getTitle()));
        result.put("introductionPlan", raw.getOrDefault("introductionPlan", fallbackPlan(document)));
        result.put("citationSlots", slots);
        result.put("citationAudit", raw.getOrDefault("citationAudit", List.of()));
        result.put("introductionAnalysisDiagnostics", Map.of(
                "rawSlotCount", rawSlotCount,
                "acceptedLlmSlotCount", acceptedLlmSlotCount,
                "minimumSlotCount", minimumSlots,
                "finalSlotCount", slots.size(),
                "fallbackAddedSlotCount", Math.max(0, slots.size() - beforeFallbackSlotCount),
                "fallbackReason", fallbackReason,
                "rawTextPreview", rawText == null ? "" : truncate(rawText, 1200)
        ));
        result.put("revisionPolicy", "Only revise introduction claims whose citation support is weak, off-topic, outdated, or missing; preserve originally correct claims and citation commands.");
        result.put("rawText", rawText == null ? "" : truncate(rawText, 2000));
        return result;
    }

    private Map<String, Object> fallback(PaperTask task, LatexDocument document, String introduction, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedBy", "introduction-analysis-fallback-v1");
        result.put("degraded", true);
        result.put("paperTitle", blankToDefault(document.title(), task.getTitle()));
        result.put("introductionPlan", fallbackPlan(document));
        result.put("citationAudit", List.of(Map.of("status", "UNKNOWN", "reason", reason == null ? "model unavailable" : reason)));
        List<Map<String, Object>> fallbackSlots = fallbackSlots(task, document, introduction);
        result.put("citationSlots", fallbackSlots);
        result.put("introductionAnalysisDiagnostics", Map.of(
                "llmCallMode", "multi-call-plan-slots-audit-v1",
                "llmCallCount", 3,
                "rawSlotCount", 0,
                "acceptedLlmSlotCount", 0,
                "minimumSlotCount", minimumSlotCount(introduction),
                "finalSlotCount", fallbackSlots.size(),
                "fallbackAddedSlotCount", fallbackSlots.size(),
                "fallbackReason", reason == null ? "model unavailable" : reason,
                "rawTextPreview", ""
        ));
        result.put("revisionPolicy", "Preserve correct existing citations; use retrieved evidence only to flag or improve weak introduction support.");
        return result;
    }

    private List<Map<String, Object>> fallbackSlots(PaperTask task, LatexDocument document, String introduction) {
        Set<Map<String, Object>> slots = new LinkedHashSet<>();
        String title = blankToDefault(document.title(), task.getTitle());
        for (FallbackClaim fallbackClaim : fallbackClaimsFromIntroduction(introduction)) {
            String category = dynamicCategory(fallbackClaim.claim());
            addFallbackSlot(slots, category, fallbackClaim.claim(), fallbackQueries(category, fallbackClaim.claim()), fallbackClaim.existingCitationKeys());
            if (slots.size() >= 14) return new ArrayList<>(slots);
        }
        String lower = (title + " " + introduction).toLowerCase();
        if (lower.contains("fda") || lower.contains("frequency diverse")) {
            addFallbackSlot(slots, "FDA-MIMO anti-jamming", "FDA-MIMO radar provides range-angle-dependent degrees of freedom for target detection and jamming suppression.", List.of(
                    "\"FDA-MIMO radar\" \"mainlobe\" jamming suppression",
                    "\"frequency diverse array MIMO\" radar deceptive jamming suppression",
                    "\"FDA-MIMO\" \"range-angle-dependent\" radar"));
        }
        if (lower.contains("polar")) {
            addFallbackSlot(slots, "Polarimetric processing", "Polarization diversity can support mainlobe interference or self-protection jamming suppression.", List.of(
                    "\"polarimetric radar\" \"mainlobe interference suppression\"",
                    "\"transmit polarization optimization\" \"MIMO radar\"",
                    "\"polarimetric FDA-MIMO radar\" target detection"));
        }
        if (lower.contains("constant") || lower.contains("modulus") || lower.contains("unimodular")) {
            addFallbackSlot(slots, "Constant-modulus waveform design", "Constant-modulus or unimodular waveform design is important for practical MIMO radar transmitters and low-sidelobe performance.", List.of(
                    "\"constant modulus\" \"MIMO radar\" \"waveform design\"",
                    "\"unimodular sequences\" \"low autocorrelation sidelobes\" radar",
                    "\"constant modulus waveform design\" radar \"SINR\""));
        }
        if (lower.contains("sdp") || lower.contains("semidefinite") || lower.contains("manifold") || lower.contains("majorization")) {
            addFallbackSlot(slots, "Optimization methods", "SDP, MM, cyclic, and manifold optimization are common approaches for constrained radar waveform design.", List.of(
                    "\"semidefinite programming\" \"constant modulus\" radar waveform design",
                    "\"majorization-minimization\" \"MIMO radar\" waveform design",
                    "\"manifold optimization\" \"MIMO radar\" waveform design"));
        }
        return new ArrayList<>(slots);
    }

    private void mergeFallbackSlots(List<Map<String, Object>> slots, List<Map<String, Object>> fallbackSlots, int minimumSlots) {
        Set<String> existing = slots.stream()
                .map(slot -> claimFingerprint(string(slot.get("claim"))))
                .filter(text -> !text.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int index = slots.size() + 1;
        for (Map<String, Object> fallbackSlot : fallbackSlots) {
            if (slots.size() >= minimumSlots) break;
            String fingerprint = claimFingerprint(string(fallbackSlot.get("claim")));
            if (fingerprint.isBlank() || existing.contains(fingerprint)) continue;
            Map<String, Object> copy = new LinkedHashMap<>(fallbackSlot);
            copy.put("id", "slot-" + index++);
            slots.add(copy);
            existing.add(fingerprint);
        }
    }

    private int minimumSlotCount(String introduction) {
        int citationCount = 0;
        Matcher citeMatcher = Pattern.compile("\\\\cite[a-zA-Z*]*(?:\\s*\\[[^]]*]){0,2}\\s*\\{[^{}]+}").matcher(introduction == null ? "" : introduction);
        while (citeMatcher.find()) citationCount++;
        int sentenceCount = Math.max(1, cleanedSentences(introduction).size());
        return Math.max(6, Math.min(14, Math.max(citationCount / 2, sentenceCount / 3)));
    }

    private List<FallbackClaim> fallbackClaimsFromIntroduction(String introduction) {
        List<FallbackClaim> claims = new ArrayList<>();
        for (String sentence : cleanedSentences(introduction)) {
            String lower = sentence.toLowerCase();
            if (sentence.length() < 60 || sentence.length() > 360) continue;
            if (lower.contains("remainder of this") || lower.contains("organized as follows")) continue;
            boolean hasCitation = !citationKeys(sentence).isEmpty();
            boolean looksLikeClaim = hasCitation
                    || lower.contains("challenge") || lower.contains("however") || lower.contains("existing")
                    || lower.contains("require") || lower.contains("important") || lower.contains("provide")
                    || lower.contains("enable") || lower.contains("suppress") || lower.contains("improve")
                    || lower.contains("recent") || lower.contains("widely") || lower.contains("has been")
                    || lower.contains("can ") || lower.contains("may ") || lower.contains("is able to");
            if (!looksLikeClaim) continue;
            String claim = stripLatexCitations(sentence);
            if (claim.isBlank()) continue;
            claims.add(new FallbackClaim(claim, citationKeys(sentence)));
            if (claims.size() >= 14) break;
        }
        return claims;
    }

    private List<String> cleanedSentences(String introduction) {
        String normalized = introduction == null ? "" : introduction.replaceAll("(?s)\\\\begin\\{abstract}.*?\\\\end\\{abstract}", " ")
                .replaceAll("\\\\(?!cite)[a-zA-Z]+\\*?(?:\\s*\\[[^]]*])?\\s*\\{([^{}]*)}", "$1")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) return List.of();
        List<String> sentences = new ArrayList<>();
        for (String sentence : normalized.split("(?<=[.!?])\\s+")) {
            String cleaned = sentence.trim();
            if (!cleaned.isBlank()) sentences.add(cleaned);
        }
        return sentences;
    }

    private List<String> citationKeys(String sentence) {
        List<String> keys = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\\\cite[a-zA-Z*]*(?:\\s*\\[[^]]*]){0,2}\\s*\\{([^{}]+)}").matcher(sentence == null ? "" : sentence);
        while (matcher.find()) {
            for (String key : matcher.group(1).split(",")) {
                String cleaned = key.trim();
                if (!cleaned.isBlank()) keys.add(cleaned);
            }
        }
        return keys;
    }

    private String stripLatexCitations(String sentence) {
        return (sentence == null ? "" : sentence)
                .replaceAll("\\\\cite[a-zA-Z*]*(?:\\s*\\[[^]]*]){0,2}\\s*\\{[^{}]+}", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String dynamicCategory(String claim) {
        String text = claim == null ? "" : claim.toLowerCase();
        if (text.contains("residual network") || text.contains("deep") || text.contains("lstm") || text.contains("learning")) return "Learning-driven waveform design";
        if (text.contains("semidefinite") || text.contains("sdp") || text.contains("manifold") || text.contains("majorization") || text.contains("optimization") || text.contains("nonconvex")) return "Optimization methods";
        if (text.contains("polar")) return "Polarimetric processing";
        if (text.contains("constant") || text.contains("unimodular") || text.contains("sidelobe") || text.contains("waveform")) return "Waveform design";
        if (text.contains("fda") || text.contains("frequency diverse") || text.contains("jamming") || text.contains("interference")) return "FDA-MIMO anti-jamming";
        if (text.contains("gap") || text.contains("existing") || text.contains("separately")) return "Related work gap";
        return "Introduction claim";
    }

    private List<String> fallbackQueries(String category, String claim) {
        String text = (category + " " + claim).toLowerCase();
        List<String> queries = new ArrayList<>();
        if (text.contains("learning") || text.contains("deep") || text.contains("lstm") || text.contains("residual")) {
            queries.add("\"deep learning\" \"MIMO radar\" waveform design");
            queries.add("\"constant modulus\" radar waveform \"deep learning\"");
            queries.add("\"model-based learning\" \"MIMO radar\" waveform design");
        } else if (text.contains("semidefinite") || text.contains("sdp") || text.contains("relaxation")) {
            queries.add("\"semidefinite programming\" \"constant modulus\" radar waveform design");
            queries.add("\"convex relaxation\" \"MIMO radar\" waveform design");
            queries.add("\"constant modulus\" radar waveform design computational complexity");
        } else if (text.contains("nonconvex") || text.contains("high-dimensional") || text.contains("coupled")) {
            queries.add("\"nonconvex optimization\" \"MIMO radar\" waveform design");
            queries.add("\"joint optimization\" waveform polarization radar");
            queries.add("\"SINR maximization\" \"constant modulus\" radar waveform");
        } else if (text.contains("manifold") || text.contains("majorization") || text.contains("low-autocorrelation") || text.contains("sidelobe") || text.contains("unimodular")) {
            queries.add("\"majorization-minimization\" unimodular sequence design");
            queries.add("\"manifold optimization\" \"MIMO radar\" waveform design");
            queries.add("\"low autocorrelation sidelobes\" unimodular sequences radar");
        } else if (text.contains("polar") && (text.contains("fda") || text.contains("frequency diverse"))) {
            queries.add("\"polarimetric FDA-MIMO radar\" target detection");
            queries.add("\"polarimetric FDA-MIMO\" waveform design");
            queries.add("\"transmit polarization optimization\" \"FDA-MIMO\" radar");
        } else if (text.contains("polar")) {
            queries.add("\"polarimetric radar\" \"mainlobe interference suppression\"");
            queries.add("\"transmit polarization optimization\" \"MIMO radar\"");
            queries.add("\"polarization mismatch\" radar jamming suppression");
        } else if (text.contains("fda") || text.contains("frequency diverse") || text.contains("jamming") || text.contains("interference")) {
            queries.add("\"FDA-MIMO radar\" \"mainlobe\" jamming suppression");
            queries.add("\"frequency diverse array MIMO\" radar deceptive jamming suppression");
            queries.add("\"FDA-MIMO\" \"range-angle-dependent\" radar");
        } else if (text.contains("constant") || text.contains("waveform")) {
            queries.add("\"constant modulus\" \"MIMO radar\" waveform design");
            queries.add("\"MIMO radar\" waveform diversity coherent interference");
            queries.add("\"radar waveform design\" SINR constant modulus");
        }
        if (queries.isEmpty()) {
            String query = String.join(" ", defaultCoreTerms(claim)).trim();
            if (!query.toLowerCase().contains("radar")) query = query + " radar";
            if (!query.isBlank()) queries.add(query);
        }
        return queries.stream().filter(q -> q != null && !q.isBlank()).limit(3).toList();
    }

    private String claimFingerprint(String claim) {
        if (claim == null) return "";
        return claim.toLowerCase().replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
    }

    private void addFallbackSlot(Set<Map<String, Object>> slots, String category, String claim, List<String> queries) {
        addFallbackSlot(slots, category, claim, queries, List.of());
    }

    private void addFallbackSlot(Set<Map<String, Object>> slots, String category, String claim, List<String> queries, List<String> existingCitationKeys) {
        slots.add(slot("slot-" + (slots.size() + 1), category, claim, "NEEDS_SUPPORT", queries, existingCitationKeys, List.of()));
    }

    private record FallbackClaim(String claim, List<String> existingCitationKeys) {}

    private Map<String, Object> slot(String id, String category, String claim, String need, List<String> queries, List<String> existingCitationKeys, List<String> coreTerms) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("id", id);
        slot.put("category", category);
        slot.put("claim", claim);
        slot.put("citationNeed", need);
        slot.put("coreTerms", coreTerms == null || coreTerms.isEmpty() ? defaultCoreTerms(category + " " + claim + " " + String.join(" ", queries)) : coreTerms);
        slot.put("queries", queries.stream().filter(q -> q != null && !q.isBlank()).limit(4).toList());
        slot.put("existingCitationKeys", existingCitationKeys);
        return slot;
    }

    private List<String> defaultCoreTerms(String text) {
        Set<String> stop = Set.of("the", "and", "for", "with", "that", "this", "using", "based", "method", "methods", "design", "study", "analysis", "performance", "existing", "work", "works", "need", "support");
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("[A-Za-z][A-Za-z0-9-]{2,}").matcher(text == null ? "" : text);
        while (matcher.find()) {
            String term = matcher.group();
            String lower = term.toLowerCase();
            if (!stop.contains(lower)) terms.add(term);
            if (terms.size() >= 10) break;
        }
        return List.copyOf(terms);
    }

    private Object fallbackPlan(LatexDocument document) {
        return Map.of("paragraphs", List.of(
                "Establish the application background and interference challenge.",
                "Review technical foundations and related work categories.",
                "Identify limitations of existing work and state the gap.",
                "Summarize the proposed method and contributions without unsupported claims."));
    }

    private String introductionText(LatexDocument document) {
        return document.sections().stream()
                .filter(section -> section.role() == LatexSectionRole.INTRO || title(section).contains("intro"))
                .findFirst()
                .map(LatexSection::rawText)
                .orElseGet(() -> document.sections().isEmpty() ? "" : document.sections().get(0).rawText());
    }

    private String paperOverview(LatexDocument document) {
        StringBuilder builder = new StringBuilder();
        for (LatexSection section : document.sections()) {
            builder.append("- ").append(section.title()).append(" (role=").append(section.role()).append(")\n")
                    .append(truncate((section.rawText() == null ? "" : section.rawText()).replaceAll("\\s+", " ").trim(), 800)).append("\n");
        }
        return builder.toString();
    }

    private Map<String, Object> readMap(String text) throws JsonProcessingException {
        String json = extractJsonObject(text == null ? "" : text);
        JsonNode node = objectMapper.readTree(json);
        return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : "{}";
    }

    private List<String> defaultQueries(String claim) {
        String query = claim.replaceAll("[^A-Za-z0-9 -]", " ").replaceAll("\\s+", " ").trim();
        if (query.split("\\s+").length > 12) {
            query = String.join(" ", List.of(query.split("\\s+")).subList(0, 12));
        }
        return query.isBlank() ? List.of() : List.of(query);
    }

    private Object firstNonNull(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            String text = string(item);
            if (!text.isBlank()) values.add(text);
        }
        return values;
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String stringOr(Object value, String fallback) { String text = string(value); return text.isBlank() ? fallback : text; }
    private String title(LatexSection section) { return section.title() == null ? "" : section.title().toLowerCase(); }
    private String blankToDefault(String value, String fallback) { return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value; }
    private String truncate(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max) + " ..."; }
    private String toJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { return "{}"; } }
}
