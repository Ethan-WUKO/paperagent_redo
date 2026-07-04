package com.yanban.paper.latex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.service.PaperModelClient;
import com.yanban.paper.service.PaperPromptService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LatexRoleRecognitionService {

    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final ObjectMapper objectMapper;

    public LatexRoleRecognitionService() {
        this.promptService = null;
        this.modelClient = null;
        this.objectMapper = new ObjectMapper();
    }

    @Autowired
    public LatexRoleRecognitionService(PaperPromptService promptService,
                                       ObjectProvider<PaperModelClient> modelClientProvider,
                                       ObjectMapper objectMapper) {
        this.promptService = promptService;
        this.modelClient = modelClientProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    public RoleRecognitionResult recognize(LatexDocument document) {
        List<RecognizedSectionRole> heuristicRoles = document.sections().stream()
                .map(section -> new RecognizedSectionRole(
                        section.orderIndex(),
                        section.title(),
                        section.role(),
                        confidence(section),
                        "heuristic"
                ))
                .toList();

        List<RecognizedSectionRole> roles = llmConfirm(document, heuristicRoles);
        List<LatexSectionRole> missing = missingCoreRoles(roles);
        List<StructureClarificationCandidate> clarifications = detectClarifications(document, roles);
        return new RoleRecognitionResult(roles, clarifications, missing);
    }

    private List<RecognizedSectionRole> llmConfirm(LatexDocument document, List<RecognizedSectionRole> heuristicRoles) {
        if (promptService == null || modelClient == null || document.sections().isEmpty()) {
            return heuristicRoles;
        }
        try {
            String prompt = promptService.render("role-confirm", Map.of(
                    "targetLanguage", "en",
                    "paperTitle", blankToDefault(document.title(), document.sourcePath()),
                    "sectionSignals", sectionSignals(document, heuristicRoles)
            ));
            String text = modelClient.complete("You return strict JSON only and choose roles only from the provided enum.", prompt, 0.1, 2048);
            List<RecognizedSectionRole> confirmed = parseRoleConfirm(text, heuristicRoles);
            return confirmed.isEmpty() ? heuristicRoles : confirmed;
        } catch (Exception ignored) {
            return heuristicRoles;
        }
    }

    private String sectionSignals(LatexDocument document, List<RecognizedSectionRole> heuristicRoles) {
        Map<Integer, RecognizedSectionRole> byOrder = new LinkedHashMap<>();
        heuristicRoles.forEach(role -> byOrder.put(role.sectionOrderIndex(), role));
        StringBuilder builder = new StringBuilder();
        for (LatexSection section : document.sections()) {
            long citationCount = document.citationUsages().stream()
                    .filter(cite -> cite.startOffset() >= section.startOffset() && cite.endOffset() <= section.endOffset())
                    .count();
            String sample = section.rawText() == null ? "" : section.rawText().replaceAll("\\s+", " ").trim();
            if (sample.length() > 500) sample = sample.substring(0, 500) + " ...";
            RecognizedSectionRole heuristic = byOrder.get(section.orderIndex());
            builder.append("- orderIndex=").append(section.orderIndex())
                    .append("; level=").append(section.level())
                    .append("; title=").append(section.title())
                    .append("; heuristicRole=").append(heuristic == null ? section.role() : heuristic.role())
                    .append("; citationCount=").append(citationCount)
                    .append("; sample=").append(sample)
                    .append('\n');
        }
        return builder.toString();
    }

    private List<RecognizedSectionRole> parseRoleConfirm(String text, List<RecognizedSectionRole> fallback) throws Exception {
        if (text == null || text.isBlank()) return List.of();
        Map<Integer, RecognizedSectionRole> fallbackByOrder = new LinkedHashMap<>();
        fallback.forEach(role -> fallbackByOrder.put(role.sectionOrderIndex(), role));
        JsonNode root = objectMapper.readTree(extractJsonObject(text));
        JsonNode sections = root.path("sections");
        if (!sections.isArray()) return List.of();
        Map<Integer, RecognizedSectionRole> confirmedByOrder = new LinkedHashMap<>();
        for (JsonNode node : sections) {
            int order = node.path("orderIndex").asInt(Integer.MIN_VALUE);
            RecognizedSectionRole fallbackRole = fallbackByOrder.get(order);
            if (fallbackRole == null) continue;
            LatexSectionRole role = parseRole(node.path("role").asText(""));
            if (role == LatexSectionRole.REFERENCES && !isStrictReferencesTitle(fallbackRole.title())) {
                role = fallbackRole.role() == LatexSectionRole.REFERENCES ? LatexSectionRole.UNKNOWN : fallbackRole.role();
            }
            if (role == null) role = fallbackRole.role();
            double confidence = Math.max(0.0, Math.min(1.0, node.path("confidence").asDouble(0.65)));
            confirmedByOrder.put(order, new RecognizedSectionRole(order, fallbackRole.title(), role, confidence, "llm"));
        }
        List<RecognizedSectionRole> result = new ArrayList<>();
        for (RecognizedSectionRole role : fallback) {
            result.add(confirmedByOrder.getOrDefault(role.sectionOrderIndex(), role));
        }
        return result;
    }

    private LatexSectionRole parseRole(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        return switch (value) {
            case "intro", "introduction" -> LatexSectionRole.INTRO;
            case "relatedwork", "background" -> LatexSectionRole.RELATED_WORK;
            case "method", "methods", "approach" -> LatexSectionRole.METHOD;
            case "experiments", "experiment", "evaluation" -> LatexSectionRole.EXPERIMENTS;
            case "results", "result" -> LatexSectionRole.RESULTS;
            case "discussion" -> LatexSectionRole.DISCUSSION;
            case "conclusion", "conclusions" -> LatexSectionRole.CONCLUSION;
            case "abstract" -> LatexSectionRole.ABSTRACT;
            case "references", "reference", "bibliography" -> LatexSectionRole.REFERENCES;
            case "appendix", "appendices" -> LatexSectionRole.APPENDIX;
            case "unknown", "" -> LatexSectionRole.UNKNOWN;
            default -> null;
        };
    }

    private boolean isStrictReferencesTitle(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[0-9ivxlcdm.\\-:、]+\\s*", "").trim();
        return t.equals("reference")
                || t.equals("references")
                || t.equals("bibliography")
                || t.equals("参考文献")
                || t.equals("文献")
                || t.equals("参考文献列表");
    }

    private String extractJsonObject(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    private double confidence(LatexSection section) {
        if (section.role() == LatexSectionRole.UNKNOWN) return 0.2;
        String title = section.title() == null ? "" : section.title().toLowerCase(Locale.ROOT);
        if (title.contains("introduction") || title.contains("related work") || title.contains("method")
                || title.contains("experiment") || title.contains("conclusion") || title.contains("abstract")) {
            return 0.85;
        }
        if (title.contains("引言") || title.contains("绪论") || title.contains("相关工作") || title.contains("方法")
                || title.contains("实验") || title.contains("结论") || title.contains("摘要")) {
            return 0.85;
        }
        return 0.65;
    }

    private List<LatexSectionRole> missingCoreRoles(List<RecognizedSectionRole> roles) {
        Set<LatexSectionRole> present = EnumSet.noneOf(LatexSectionRole.class);
        roles.forEach(role -> present.add(role.role()));
        List<LatexSectionRole> missing = new ArrayList<>();
        for (LatexSectionRole role : List.of(LatexSectionRole.INTRO, LatexSectionRole.METHOD, LatexSectionRole.EXPERIMENTS, LatexSectionRole.CONCLUSION)) {
            if (!present.contains(role)) {
                missing.add(role);
            }
        }
        return missing;
    }

    private List<StructureClarificationCandidate> detectClarifications(LatexDocument document, List<RecognizedSectionRole> roles) {
        boolean hasExplicitRelatedWork = roles.stream().anyMatch(role -> role.role() == LatexSectionRole.RELATED_WORK);
        if (hasExplicitRelatedWork) {
            return List.of();
        }

        return document.sections().stream()
                .filter(section -> section.role() == LatexSectionRole.INTRO)
                .filter(section -> seemsRelatedWorkIntegrated(section, document))
                .findFirst()
                .map(section -> List.of(new StructureClarificationCandidate(
                        "RELATED_WORK_PLACEMENT",
                        true,
                        "检测到论文没有单独的 Related Work 章节，但引言中似乎包含引用密集的相关工作内容。请确认是否保持当前章节安排。",
                        List.of("KEEP_IN_INTRO", "SPLIT_RELATED_WORK"),
                        "KEEP_IN_INTRO",
                        section.orderIndex()
                )))
                .orElse(List.of());
    }

    private boolean seemsRelatedWorkIntegrated(LatexSection section, LatexDocument document) {
        long citationCount = document.citationUsages().stream()
                .filter(cite -> cite.startOffset() >= section.startOffset() && cite.endOffset() <= section.endOffset())
                .count();
        String raw = section.rawText().toLowerCase(Locale.ROOT);
        boolean hasCue = raw.contains("related work") || raw.contains("prior work") || raw.contains("previous studies")
                || raw.contains("existing methods") || raw.contains("相关工作") || raw.contains("已有研究") || raw.contains("现有方法");
        return citationCount >= 2 || hasCue;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }
}
