package com.yanban.paper.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Deterministic audit of the exact TeX and BibTeX bytes selected for export. */
@Service
public class PaperFinalAuditService {

    private static final Pattern CITE = Pattern.compile(
            "\\\\(?:cite|citep|citet|parencite|textcite|autocite)\\*?(?:\\s*\\[[^]]*]){0,2}\\s*\\{([^{}]+)}");
    private static final Pattern BIB_ENTRY = Pattern.compile("(?m)^\\s*@[A-Za-z]+\\s*\\{\\s*([^,\\s]+)");
    private static final Pattern LABEL = Pattern.compile("\\\\label\\s*\\{([^{}]+)}");
    private static final Pattern REF = Pattern.compile("\\\\(?:ref|eqref|cref|Cref|autoref|pageref)\\*?\\s*\\{([^{}]+)}");
    private static final Pattern SLOT = Pattern.compile("\\\\yanbancitationslot\\s*\\{\\d+}");
    private static final Pattern COMMENT_LINE = Pattern.compile("(?m)^\\s*%.*$");
    private static final String RECOMMENDED_BEGIN = "% === YANBAN_RECOMMENDED_BEGIN ===";
    private static final String RECOMMENDED_END = "% === YANBAN_RECOMMENDED_END ===";

    public AuditResult audit(String tex,
                             String bib,
                             PaperCitationApplyService.CitationApplyResult citationResult) {
        String finalTex = tex == null ? "" : tex;
        String finalBib = bib == null ? "" : bib;
        String activeTex = COMMENT_LINE.matcher(finalTex).replaceAll("");
        List<AuditIssue> issues = new ArrayList<>();

        List<String> bibliographyKeyList = listValues(BIB_ENTRY, finalBib);
        Set<String> bibliographyKeys = new LinkedHashSet<>(bibliographyKeyList);
        if (bibliographyKeys.size() != bibliographyKeyList.size()) {
            issues.add(new AuditIssue("DUPLICATE_BIBLIOGRAPHY_KEY", "blocker",
                    "The merged bibliography defines the same key more than once."));
        }
        Set<String> citationKeys = new LinkedHashSet<>();
        Matcher citeMatcher = CITE.matcher(activeTex);
        CitationSpan previous = null;
        while (citeMatcher.find()) {
            List<String> keys = splitKeys(citeMatcher.group(1));
            Set<String> unique = new LinkedHashSet<>(keys);
            citationKeys.addAll(unique);
            if (unique.size() != keys.size()) {
                addOnce(issues, "DUPLICATE_CITATION_KEY", "minor",
                        "A citation command contains the same bibliography key more than once.");
            }
            if (previous != null && activeTex.substring(previous.end(), citeMatcher.start()).isBlank()) {
                addOnce(issues, "ADJACENT_CITATION_COMMANDS", "minor",
                        "Adjacent citation commands should be merged into one deduplicated command.");
            }
            previous = new CitationSpan(citeMatcher.start(), citeMatcher.end());
        }

        Set<String> missingBibliography = new LinkedHashSet<>(citationKeys);
        missingBibliography.removeAll(bibliographyKeys);
        if (!missingBibliography.isEmpty()) {
            issues.add(new AuditIssue("MISSING_BIBLIOGRAPHY_KEY", "blocker",
                    "Cited keys are absent from the merged bibliography: " + missingBibliography));
        }
        if (SLOT.matcher(activeTex).find()) {
            issues.add(new AuditIssue("RESIDUAL_CITATION_SLOT", "blocker",
                    "A protected citation-slot marker remains in the exported manuscript."));
        }

        Set<String> labels = values(LABEL, activeTex);
        Set<String> references = values(REF, activeTex);
        references.removeAll(labels);
        if (!references.isEmpty()) {
            issues.add(new AuditIssue("MISSING_REFERENCE_LABEL", "major",
                    "Cross-reference labels are missing from the exported manuscript: " + references));
        }

        validateRecommendedBlocks(finalBib, issues);
        validateAppliedPatches(activeTex, citationResult, issues);

        String status = issues.stream().anyMatch(issue -> "blocker".equals(issue.severity()) || "major".equals(issue.severity()))
                ? "FAIL"
                : issues.isEmpty() ? "PASS" : "WARN";
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("citationKeyCount", citationKeys.size());
        counts.put("bibliographyEntryCount", bibliographyKeys.size());
        counts.put("appliedPatchCount", citationResult == null ? 0 : citationResult.appliedPatches().size());
        counts.put("unappliedPatchCount", citationResult == null ? 0 : citationResult.manualPatches().size());
        return new AuditResult(status, List.copyOf(issues), Map.copyOf(counts));
    }

    private void validateRecommendedBlocks(String bib, List<AuditIssue> issues) {
        int beginCount = count(bib, RECOMMENDED_BEGIN);
        int endCount = count(bib, RECOMMENDED_END);
        if (beginCount != endCount) {
            addOnce(issues, "RECOMMENDED_BIB_MARKER_MISMATCH", "major",
                    "Recommended bibliography BEGIN/END marker counts do not match.");
        }
        int offset = 0;
        while (true) {
            int begin = bib.indexOf(RECOMMENDED_BEGIN, offset);
            if (begin < 0) break;
            int end = bib.indexOf(RECOMMENDED_END, begin + RECOMMENDED_BEGIN.length());
            if (end < 0) {
                issues.add(new AuditIssue("UNCLOSED_RECOMMENDED_BIB_BLOCK", "major",
                        "A recommended BibTeX block has no closing marker."));
                break;
            }
            String block = bib.substring(begin + RECOMMENDED_BEGIN.length(), end);
            if (!BIB_ENTRY.matcher(block).find()) {
                addOnce(issues, "EMPTY_RECOMMENDED_BIB_BLOCK", "major",
                        "A recommended bibliography marker pair does not enclose its BibTeX entry.");
            }
            offset = end + RECOMMENDED_END.length();
        }
    }

    private void validateAppliedPatches(String tex,
                                        PaperCitationApplyService.CitationApplyResult citationResult,
                                        List<AuditIssue> issues) {
        if (citationResult == null) return;
        Set<String> cited = new LinkedHashSet<>();
        Matcher matcher = CITE.matcher(tex);
        while (matcher.find()) cited.addAll(splitKeys(matcher.group(1)));
        for (Map<String, Object> patch : citationResult.appliedPatches()) {
            Object values = patch.get("bibKeys");
            if (!(values instanceof Iterable<?> keys)) continue;
            for (Object value : keys) {
                String key = value == null ? "" : String.valueOf(value).trim();
                if (!key.isBlank() && !cited.contains(key)) {
                    issues.add(new AuditIssue("APPLIED_PATCH_NOT_IN_FINAL_TEX", "blocker",
                            "Suggestion #" + patch.get("suggestionId") + " is recorded as applied but key " + key + " is absent from the final TeX."));
                }
            }
        }
    }

    private Set<String> values(Pattern pattern, String text) {
        return new LinkedHashSet<>(listValues(pattern, text));
    }

    private List<String> listValues(Pattern pattern, String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) result.add(matcher.group(1).trim());
        return result;
    }

    private int count(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private List<String> splitKeys(String value) {
        List<String> result = new ArrayList<>();
        for (String key : value.split(",")) {
            if (!key.isBlank()) result.add(key.trim());
        }
        return result;
    }

    private void addOnce(List<AuditIssue> issues, String code, String severity, String message) {
        if (issues.stream().noneMatch(issue -> code.equals(issue.code()))) {
            issues.add(new AuditIssue(code, severity, message));
        }
    }

    public record AuditIssue(String code, String severity, String message) {}

    public record AuditResult(String status, List<AuditIssue> issues, Map<String, Object> counts) {
        public static AuditResult notRun() {
            return new AuditResult("NOT_RUN", List.of(), Map.of());
        }
    }

    private record CitationSpan(int start, int end) {}
}
