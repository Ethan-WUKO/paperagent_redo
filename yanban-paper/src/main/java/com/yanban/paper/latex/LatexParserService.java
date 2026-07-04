package com.yanban.paper.latex;

import com.yanban.paper.latex.LatexLintIssue.Severity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class LatexParserService {

    private static final Pattern BEGIN_DOCUMENT = Pattern.compile("\\\\begin\\s*\\{document\\}");
    private static final Pattern SECTION_COMMAND = Pattern.compile("\\\\(part|chapter|section|subsection|subsubsection|paragraph)(\\*)?\\s*\\{([^{}]*)\\}");
    private static final Pattern TITLE_COMMAND = Pattern.compile("\\\\title\\s*\\{([^{}]*)\\}", Pattern.DOTALL);
    private static final Pattern AUTHOR_COMMAND = Pattern.compile("\\\\author\\s*\\{([^{}]*)\\}", Pattern.DOTALL);
    private static final Pattern KEYWORDS_COMMAND = Pattern.compile("\\\\keywords\\s*\\{([^{}]*)\\}", Pattern.DOTALL);
    private static final Pattern ENV_BOUNDARY = Pattern.compile("\\\\(begin|end)\\s*\\{([^{}]+)\\}");
    private static final Pattern CITE_COMMAND = Pattern.compile("\\\\(cite|citep|citet|citeauthor|citeyear|parencite|textcite|autocite)\\*?(?:\\s*\\[[^]]*]){0,2}\\s*\\{([^{}]+)\\}");
    private static final Pattern REF_COMMAND = Pattern.compile("\\\\(ref|eqref|cref|Cref|autoref|pageref)\\*?\\s*\\{([^{}]+)\\}");
    private static final Pattern LABEL_COMMAND = Pattern.compile("\\\\label\\s*\\{([^{}]+)\\}");
    private static final Pattern GRAPHICS_COMMAND = Pattern.compile("\\\\includegraphics(?:\\s*\\[[^]]*])?\\s*\\{([^{}]+)\\}");
    private static final Pattern CAPTION_COMMAND = Pattern.compile("\\\\caption(?:\\s*\\[[^]]*])?\\s*\\{([^{}]*)\\}", Pattern.DOTALL);
    private static final Pattern BIB_ENTRY = Pattern.compile("@([A-Za-z]+)\\s*\\{\\s*([^,\\s]+)\\s*,", Pattern.MULTILINE);
    private static final Pattern BIB_ITEM = Pattern.compile("\\\\bibitem(?:\\s*\\[[^]]*])?\\s*\\{([^{}]+)\\}");

    public LatexDocument parse(String sourcePath, String texContent, Map<String, String> bibFiles) {
        String normalizedSource = sourcePath == null ? "main.tex" : sourcePath;
        List<String> duplicateBibKeys = new ArrayList<>();
        Map<String, LatexBibEntry> bibliography = parseBibliography(bibFiles == null ? Map.of() : bibFiles, texContent, duplicateBibKeys);
        String preamble = extractPreamble(texContent);
        List<LatexSection> sections = parseSections(texContent);
        String frontMatter = extractFrontMatter(texContent, sections);
        String metadataSource = preamble + "\n" + frontMatter;
        List<LatexCitationUsage> citationUsages = parseCitations(texContent);
        List<LatexCrossReference> crossReferences = parseCrossReferences(texContent);
        List<LatexFloat> floats = parseFloats(texContent, crossReferences);
        List<LatexProtectedSpan> protectedSpans = parseProtectedSpans(texContent);
        List<LatexLintIssue> lintIssues = lint(texContent, bibliography, duplicateBibKeys, citationUsages, crossReferences);

        return new LatexDocument(
                normalizedSource,
                cleanLatexText(firstGroup(TITLE_COMMAND, metadataSource)),
                splitPeople(cleanLatexText(firstGroup(AUTHOR_COMMAND, metadataSource))),
                splitKeywords(cleanLatexText(firstGroup(KEYWORDS_COMMAND, metadataSource))),
                preamble,
                frontMatter,
                sections,
                protectedSpans,
                floats,
                citationUsages,
                crossReferences,
                bibliography,
                lintIssues
        );
    }

    private String extractPreamble(String texContent) {
        Matcher matcher = BEGIN_DOCUMENT.matcher(texContent);
        if (matcher.find()) {
            return texContent.substring(0, matcher.start());
        }
        return "";
    }

    private String extractFrontMatter(String texContent, List<LatexSection> sections) {
        int beginEnd = beginDocumentEnd(texContent);
        if (beginEnd < 0 || sections == null || sections.isEmpty()) return "";
        int firstSectionStart = sections.stream().mapToInt(LatexSection::startOffset).min().orElse(-1);
        if (firstSectionStart <= beginEnd || firstSectionStart > texContent.length()) return "";
        return texContent.substring(beginEnd, firstSectionStart);
    }

    private List<LatexSection> parseSections(String texContent) {
        List<SectionHit> hits = new ArrayList<>();
        Matcher matcher = SECTION_COMMAND.matcher(texContent);
        while (matcher.find()) {
            String command = matcher.group(1);
            String title = cleanLatexText(matcher.group(3));
            hits.add(new SectionHit(matcher.start(), matcher.end(), levelOf(command), command, matcher.group(2) == null, title));
        }

        List<LatexSection> sections = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SectionHit hit = hits.get(i);
            int end = i + 1 < hits.size() ? hits.get(i + 1).start : texContent.length();
            sections.add(new LatexSection(
                    i,
                    hit.level,
                    hit.command,
                    hit.numbered,
                    hit.title,
                    guessRole(hit.title),
                    hit.start,
                    end,
                    texContent.substring(hit.start, end)
            ));
        }

        if (sections.isEmpty()) {
            int beginEnd = beginDocumentEnd(texContent);
            int endDocument = texContent.indexOf("\\end{document}");
            int start = beginEnd >= 0 ? beginEnd : 0;
            int end = endDocument >= 0 ? endDocument : texContent.length();
            String body = texContent.substring(start, end).trim();
            if (!body.isBlank()) {
                sections.add(new LatexSection(0, 1, "body", true, "Body", LatexSectionRole.UNKNOWN, start, end, texContent.substring(start, end)));
            }
        }
        return sections;
    }

    private int beginDocumentEnd(String texContent) {
        Matcher matcher = BEGIN_DOCUMENT.matcher(texContent);
        return matcher.find() ? matcher.end() : -1;
    }

    private int levelOf(String command) {
        return switch (command) {
            case "part" -> 0;
            case "chapter" -> 1;
            case "section" -> 2;
            case "subsection" -> 3;
            case "subsubsection" -> 4;
            case "paragraph" -> 5;
            default -> 9;
        };
    }

    private LatexSectionRole guessRole(String title) {
        String t = title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (t.contains("abstract") || t.contains("摘要")) return LatexSectionRole.ABSTRACT;
        if (t.contains("introduction") || t.contains("intro") || t.contains("引言") || t.contains("绪论")) return LatexSectionRole.INTRO;
        if (t.contains("related work") || t.contains("background") || t.contains("相关工作") || t.contains("文献综述")) return LatexSectionRole.RELATED_WORK;
        if (t.contains("method") || t.contains("approach") || t.contains("方法") || t.contains("模型")) return LatexSectionRole.METHOD;
        if (t.contains("experiment") || t.contains("evaluation") || t.contains("实验") || t.contains("评估")) return LatexSectionRole.EXPERIMENTS;
        if (t.contains("result") || t.contains("结果")) return LatexSectionRole.RESULTS;
        if (t.contains("discussion") || t.contains("讨论")) return LatexSectionRole.DISCUSSION;
        if (t.contains("conclusion") || t.contains("结论") || t.contains("总结")) return LatexSectionRole.CONCLUSION;
        if (isReferencesTitle(t)) return LatexSectionRole.REFERENCES;
        if (t.contains("appendix") || t.contains("附录")) return LatexSectionRole.APPENDIX;
        return LatexSectionRole.UNKNOWN;
    }

    private boolean isReferencesTitle(String normalizedTitle) {
        String t = normalizedTitle.replaceAll("^[0-9ivxlcdm.\\-:、]+\\s*", "").trim();
        return t.equals("reference")
                || t.equals("references")
                || t.equals("bibliography")
                || t.equals("参考文献")
                || t.equals("文献")
                || t.equals("参考文献列表");
    }

    private List<LatexCitationUsage> parseCitations(String texContent) {
        List<LatexCitationUsage> citations = new ArrayList<>();
        Matcher matcher = CITE_COMMAND.matcher(texContent);
        while (matcher.find()) {
            citations.add(new LatexCitationUsage(matcher.group(1), splitKeys(matcher.group(2)), matcher.start(), matcher.end()));
        }
        return citations;
    }

    private List<LatexCrossReference> parseCrossReferences(String texContent) {
        List<LatexCrossReference> refs = new ArrayList<>();
        Matcher matcher = REF_COMMAND.matcher(texContent);
        while (matcher.find()) {
            refs.add(new LatexCrossReference(matcher.group(1), matcher.group(2).trim(), matcher.start(), matcher.end()));
        }
        return refs;
    }

    private List<LatexFloat> parseFloats(String texContent, List<LatexCrossReference> refs) {
        List<LatexFloat> floats = new ArrayList<>();
        for (String kind : List.of("figure", "table")) {
            Pattern env = Pattern.compile("\\\\begin\\s*\\{" + kind + "\\*?\\}(.*?)\\\\end\\s*\\{" + kind + "\\*?\\}", Pattern.DOTALL);
            Matcher matcher = env.matcher(texContent);
            while (matcher.find()) {
                String raw = matcher.group(0);
                String label = firstGroup(LABEL_COMMAND, raw);
                List<String> referencedBy = label == null ? List.of() : refs.stream()
                        .filter(ref -> label.equals(ref.label()))
                        .map(LatexCrossReference::command)
                        .toList();
                floats.add(new LatexFloat(
                        kind,
                        label,
                        cleanLatexText(firstGroup(CAPTION_COMMAND, raw)),
                        allGroups(GRAPHICS_COMMAND, raw),
                        referencedBy,
                        matcher.start(),
                        matcher.end(),
                        raw
                ));
            }
        }
        return floats;
    }

    private List<LatexProtectedSpan> parseProtectedSpans(String texContent) {
        List<LatexProtectedSpan> spans = new ArrayList<>();
        int counter = 1;
        counter = addRegexSpans(spans, counter, "CITE", CITE_COMMAND, texContent);
        counter = addRegexSpans(spans, counter, "REF", REF_COMMAND, texContent);
        counter = addRegexSpans(spans, counter, "LABEL", LABEL_COMMAND, texContent);
        counter = addRegexSpans(spans, counter, "GRAPHICS", GRAPHICS_COMMAND, texContent);
        counter = addMathSpans(spans, counter, texContent);
        counter = addEnvironmentSpans(spans, counter, texContent, List.of(
                "equation", "align", "gather", "multline", "figure", "table", "algorithm", "theorem", "proof", "verbatim", "lstlisting", "minted"));
        return spans.stream().sorted((a, b) -> Integer.compare(a.startOffset(), b.startOffset())).toList();
    }

    private int addRegexSpans(List<LatexProtectedSpan> spans, int counter, String kind, Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            spans.add(new LatexProtectedSpan(kind + "_" + counter++, kind, matcher.start(), matcher.end(), matcher.group(0)));
        }
        return counter;
    }

    private int addMathSpans(List<LatexProtectedSpan> spans, int counter, String text) {
        int i = 0;
        while (i < text.length()) {
            if (startsEscaped(text, i, "\\[")) {
                int end = text.indexOf("\\]", i + 2);
                if (end > i) {
                    spans.add(new LatexProtectedSpan("MATH_" + counter++, "MATH", i, end + 2, text.substring(i, end + 2)));
                    i = end + 2;
                    continue;
                }
            }
            if (startsEscaped(text, i, "\\(")) {
                int end = text.indexOf("\\)", i + 2);
                if (end > i) {
                    spans.add(new LatexProtectedSpan("MATH_" + counter++, "MATH", i, end + 2, text.substring(i, end + 2)));
                    i = end + 2;
                    continue;
                }
            }
            if (text.charAt(i) == '$' && !isEscaped(text, i)) {
                boolean display = i + 1 < text.length() && text.charAt(i + 1) == '$';
                int end = findDollarEnd(text, i + (display ? 2 : 1), display);
                if (end > i) {
                    int spanEnd = end + (display ? 2 : 1);
                    spans.add(new LatexProtectedSpan("MATH_" + counter++, "MATH", i, spanEnd, text.substring(i, spanEnd)));
                    i = spanEnd;
                    continue;
                }
            }
            i++;
        }
        return counter;
    }

    private int addEnvironmentSpans(List<LatexProtectedSpan> spans, int counter, String text, List<String> environments) {
        for (String envName : environments) {
            Pattern pattern = Pattern.compile("\\\\begin\\s*\\{" + envName + "\\*?\\}(.*?)\\\\end\\s*\\{" + envName + "\\*?\\}", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                spans.add(new LatexProtectedSpan(envName.toUpperCase(Locale.ROOT) + "_" + counter++, "ENV", matcher.start(), matcher.end(), matcher.group(0)));
            }
        }
        return counter;
    }

    private boolean startsEscaped(String text, int offset, String token) {
        return text.startsWith(token, offset);
    }

    private int findDollarEnd(String text, int from, boolean display) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == '$' && !isEscaped(text, i)) {
                if (!display) return i;
                if (i + 1 < text.length() && text.charAt(i + 1) == '$') return i;
            }
        }
        return -1;
    }

    private boolean isEscaped(String text, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private Map<String, LatexBibEntry> parseBibliography(Map<String, String> bibFiles, String texContent, List<String> duplicateKeys) {
        Map<String, LatexBibEntry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, String> bibFile : bibFiles.entrySet()) {
            parseBibFile(entries, duplicateKeys, bibFile.getKey(), bibFile.getValue());
        }
        parseInlineBibliography(entries, duplicateKeys, texContent);
        return entries;
    }

    private void parseBibFile(Map<String, LatexBibEntry> entries, List<String> duplicateKeys, String source, String bibContent) {
        Matcher matcher = BIB_ENTRY.matcher(bibContent);
        while (matcher.find()) {
            String type = matcher.group(1).toLowerCase(Locale.ROOT);
            String key = matcher.group(2).trim();
            int entryEnd = findBalancedBraceEnd(bibContent, matcher.end() - 1);
            String raw = entryEnd > matcher.start() ? bibContent.substring(matcher.start(), entryEnd + 1) : matcher.group(0);
            if (entries.containsKey(key)) {
                duplicateKeys.add(key);
            } else {
                entries.put(key, new LatexBibEntry(key, type, parseBibFields(raw), raw, source));
            }
        }
    }

    private void parseInlineBibliography(Map<String, LatexBibEntry> entries, List<String> duplicateKeys, String texContent) {
        Pattern env = Pattern.compile("\\\\begin\\s*\\{thebibliography\\}(?:\\s*\\{[^{}]*\\})?(.*?)\\\\end\\s*\\{thebibliography\\}", Pattern.DOTALL);
        Matcher envMatcher = env.matcher(texContent);
        while (envMatcher.find()) {
            String rawEnv = envMatcher.group(1);
            Matcher itemMatcher = BIB_ITEM.matcher(rawEnv);
            List<BibItemHit> hits = new ArrayList<>();
            while (itemMatcher.find()) {
                hits.add(new BibItemHit(itemMatcher.start(), itemMatcher.end(), itemMatcher.group(1).trim()));
            }
            for (int i = 0; i < hits.size(); i++) {
                BibItemHit hit = hits.get(i);
                int end = i + 1 < hits.size() ? hits.get(i + 1).start : rawEnv.length();
                String raw = rawEnv.substring(hit.start, end).trim();
                if (entries.containsKey(hit.key)) {
                    duplicateKeys.add(hit.key);
                } else {
                    entries.put(hit.key, new LatexBibEntry(hit.key, "bibitem", Map.of(), raw, "inline"));
                }
            }
        }
    }

    private Map<String, String> parseBibFields(String raw) {
        Map<String, String> fields = new HashMap<>();
        if (raw == null || raw.isBlank()) return fields;
        int open = raw.indexOf('{');
        int i = open < 0 ? 0 : open + 1;
        while (i < raw.length()) {
            while (i < raw.length() && (Character.isWhitespace(raw.charAt(i)) || raw.charAt(i) == ',')) i++;
            int nameStart = i;
            while (i < raw.length() && (Character.isLetterOrDigit(raw.charAt(i)) || raw.charAt(i) == '_' || raw.charAt(i) == '-')) i++;
            if (i <= nameStart) { i++; continue; }
            String name = raw.substring(nameStart, i).toLowerCase(Locale.ROOT);
            while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
            if (i >= raw.length() || raw.charAt(i) != '=') continue;
            i++;
            while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
            if (i >= raw.length()) break;
            String value;
            char delimiter = raw.charAt(i);
            if (delimiter == '{') {
                int end = findBalancedBraceEnd(raw, i);
                if (end <= i) break;
                value = raw.substring(i + 1, end);
                i = end + 1;
            } else if (delimiter == '"') {
                int end = i + 1;
                while (end < raw.length() && (raw.charAt(end) != '"' || isEscaped(raw, end))) end++;
                value = raw.substring(i + 1, Math.min(end, raw.length()));
                i = Math.min(end + 1, raw.length());
            } else {
                int end = i;
                while (end < raw.length() && raw.charAt(end) != ',' && raw.charAt(end) != '}') end++;
                value = raw.substring(i, end);
                i = end;
            }
            fields.put(name, cleanLatexText(value));
        }
        return fields;
    }

    private int findBalancedBraceEnd(String text, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' && !isEscaped(text, i)) depth++;
            if (c == '}' && !isEscaped(text, i)) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private List<LatexLintIssue> lint(String texContent, Map<String, LatexBibEntry> bibliography, List<String> duplicateBibKeys,
                                      List<LatexCitationUsage> citations, List<LatexCrossReference> refs) {
        List<LatexLintIssue> issues = new ArrayList<>();
        Set<String> bibKeys = bibliography.keySet();
        for (LatexCitationUsage citation : citations) {
            for (String key : citation.keys()) {
                if (!bibKeys.contains(key)) {
                    issues.add(new LatexLintIssue(Severity.BLOCKER, "DANGLING_CITE", "Citation key not found in bibliography: " + key, citation.startOffset(), citation.endOffset()));
                }
            }
        }

        Set<String> labels = new HashSet<>();
        Matcher labelMatcher = LABEL_COMMAND.matcher(texContent);
        while (labelMatcher.find()) {
            labels.add(labelMatcher.group(1).trim());
        }
        for (LatexCrossReference ref : refs) {
            if (!labels.contains(ref.label())) {
                issues.add(new LatexLintIssue(Severity.BLOCKER, "DANGLING_REF", "Reference label not found: " + ref.label(), ref.startOffset(), ref.endOffset()));
            }
        }

        issues.addAll(lintEnvironmentPairs(texContent));
        issues.addAll(lintBraceBalance(texContent));
        issues.addAll(lintDuplicateBibKeys(duplicateBibKeys));
        return issues;
    }

    private List<LatexLintIssue> lintEnvironmentPairs(String texContent) {
        List<LatexLintIssue> issues = new ArrayList<>();
        ArrayDeque<EnvHit> stack = new ArrayDeque<>();
        Matcher matcher = ENV_BOUNDARY.matcher(texContent);
        while (matcher.find()) {
            String direction = matcher.group(1);
            String name = matcher.group(2);
            if ("begin".equals(direction)) {
                stack.push(new EnvHit(name, matcher.start(), matcher.end()));
            } else if (stack.isEmpty()) {
                issues.add(new LatexLintIssue(Severity.BLOCKER, "ENV_MISMATCH", "Unexpected \\end{" + name + "}", matcher.start(), matcher.end()));
            } else {
                EnvHit begin = stack.pop();
                if (!begin.name.equals(name)) {
                    issues.add(new LatexLintIssue(Severity.BLOCKER, "ENV_MISMATCH", "Environment mismatch: \\begin{" + begin.name + "} closed by \\end{" + name + "}", begin.start, matcher.end()));
                }
            }
        }
        while (!stack.isEmpty()) {
            EnvHit begin = stack.pop();
            issues.add(new LatexLintIssue(Severity.BLOCKER, "ENV_MISMATCH", "Unclosed environment: " + begin.name, begin.start, begin.end));
        }
        return issues;
    }

    private List<LatexLintIssue> lintBraceBalance(String texContent) {
        int depth = 0;
        for (int i = 0; i < texContent.length(); i++) {
            char c = texContent.charAt(i);
            if (isEscaped(texContent, i)) continue;
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth < 0) {
                    return List.of(new LatexLintIssue(Severity.BLOCKER, "BRACE_MISMATCH", "Unexpected closing brace", i, i + 1));
                }
            }
        }
        if (depth != 0) {
            return List.of(new LatexLintIssue(Severity.BLOCKER, "BRACE_MISMATCH", "Unbalanced braces", 0, texContent.length()));
        }
        return List.of();
    }

    private List<LatexLintIssue> lintDuplicateBibKeys(List<String> duplicateBibKeys) {
        return duplicateBibKeys.stream()
                .distinct()
                .map(key -> new LatexLintIssue(Severity.BLOCKER, "DUPLICATE_BIB_KEY", "Duplicate bibliography key: " + key, -1, -1))
                .toList();
    }

    private String firstGroup(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input == null ? "" : input);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> allGroups(Pattern pattern, String input) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(input == null ? "" : input);
        while (matcher.find()) {
            values.add(cleanLatexText(matcher.group(1)));
        }
        return values;
    }

    private List<String> splitKeys(String keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys.split(",")) {
            String trimmed = key.trim();
            if (!trimmed.isBlank()) result.add(trimmed);
        }
        return result;
    }

    private List<String> splitPeople(String authors) {
        if (authors == null || authors.isBlank()) return List.of();
        return List.of(authors.split("\\s+and\\s+|\\s*,\\s*|\\s*;\\s*"));
    }

    private List<String> splitKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) return List.of();
        return List.of(keywords.split("\\s*,\\s*|\\s*;\\s*"));
    }

    private String cleanLatexText(String text) {
        if (text == null) return null;
        return text.replaceAll("\\s+", " ").trim();
    }

    private record SectionHit(int start, int end, int level, String command, boolean numbered, String title) {}
    private record BibItemHit(int start, int end, String key) {}
    private record EnvHit(String name, int start, int end) {}
}
