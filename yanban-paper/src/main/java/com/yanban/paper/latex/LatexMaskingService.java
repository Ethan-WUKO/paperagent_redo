package com.yanban.paper.latex;

import com.yanban.paper.latex.LatexLintIssue.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class LatexMaskingService {

    private static final Pattern CITE = Pattern.compile("\\\\(cite|citep|citet|citeauthor|citeyear|parencite|textcite|autocite)\\*?(?:\\s*\\[[^]]*]){0,2}\\s*\\{([^{}]+)}");
    private static final Pattern REF = Pattern.compile("\\\\(ref|eqref|cref|Cref|autoref|pageref)\\*?\\s*\\{([^{}]+)}");
    private static final Pattern LABEL = Pattern.compile("\\\\label\\s*\\{([^{}]+)}");
    private static final Pattern GRAPHICS = Pattern.compile("\\\\includegraphics(?:\\s*\\[[^]]*])?\\s*\\{([^{}]+)}");
    private static final Pattern ENV = Pattern.compile("\\\\begin\\s*\\{(equation|align|gather|multline|figure|table|algorithm|theorem|proof|verbatim|lstlisting|minted)\\*?}(.*?)\\\\end\\s*\\{\\1\\*?}", Pattern.DOTALL);
    private static final Pattern INLINE_MATH = Pattern.compile("(?<!\\\\)\\$(?!\\$)(.+?)(?<!\\\\)\\$", Pattern.DOTALL);
    private static final Pattern DISPLAY_MATH = Pattern.compile("\\\\\\[(.*?)\\\\]", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[\\[YANBAN_[A-Z]+_\\d{4}]]");
    private static final Pattern ENV_BOUNDARY = Pattern.compile("\\\\(begin|end)\\s*\\{([^{}]+)}");

    public MaskedLatexText mask(String latex) {
        String source = latex == null ? "" : latex;
        List<Span> spans = collectSpans(source);
        Map<String, String> placeholders = new LinkedHashMap<>();
        StringBuilder masked = new StringBuilder();
        int cursor = 0;
        int index = 1;
        for (Span span : spans) {
            if (span.start() < cursor) continue;
            masked.append(source, cursor, span.start());
            String placeholder = "[[YANBAN_" + span.kind() + "_" + String.format("%04d", index++) + "]]";
            placeholders.put(placeholder, source.substring(span.start(), span.end()));
            masked.append(placeholder);
            cursor = span.end();
        }
        masked.append(source.substring(cursor));
        return new MaskedLatexText(masked.toString(), placeholders, Set.copyOf(placeholders.keySet()), lint(masked.toString()));
    }

    public String unmask(String masked, Map<String, String> placeholders) {
        String result = masked == null ? "" : masked;
        if (placeholders == null) return result;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public PlaceholderValidation validatePlaceholders(String text, Set<String> inputPlaceholders) {
        Set<String> output = placeholdersIn(text);
        Set<String> expected = inputPlaceholders == null ? Set.of() : inputPlaceholders;
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(output);
        Set<String> unexpected = new LinkedHashSet<>(output);
        unexpected.removeAll(expected);
        return new PlaceholderValidation(missing.isEmpty() && unexpected.isEmpty(), missing, unexpected);
    }

    public List<LatexLintIssue> lint(String latex) {
        String source = latex == null ? "" : latex;
        List<LatexLintIssue> issues = new ArrayList<>();
        int balance = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean escaped = i > 0 && source.charAt(i - 1) == '\\';
            if (!escaped && c == '{') balance++;
            if (!escaped && c == '}') balance--;
            if (balance < 0) {
                issues.add(new LatexLintIssue(Severity.BLOCKER, "UNBALANCED_BRACE", "Unexpected closing brace", i, i + 1));
                balance = 0;
            }
        }
        if (balance > 0) {
            issues.add(new LatexLintIssue(Severity.BLOCKER, "UNBALANCED_BRACE", "Missing closing brace", source.length(), source.length()));
        }

        List<String> stack = new ArrayList<>();
        Matcher env = ENV_BOUNDARY.matcher(source);
        while (env.find()) {
            String op = env.group(1);
            String name = env.group(2);
            if ("begin".equals(op)) {
                stack.add(name);
            } else if (stack.isEmpty()) {
                issues.add(new LatexLintIssue(Severity.BLOCKER, "UNBALANCED_ENV", "Unexpected \\end{" + name + "}", env.start(), env.end()));
            } else {
                String last = stack.remove(stack.size() - 1);
                if (!last.equals(name)) {
                    issues.add(new LatexLintIssue(Severity.BLOCKER, "UNBALANCED_ENV", "Environment mismatch: expected " + last + " but got " + name, env.start(), env.end()));
                }
            }
        }
        if (!stack.isEmpty()) {
            issues.add(new LatexLintIssue(Severity.BLOCKER, "UNBALANCED_ENV", "Missing \\end{" + stack.get(stack.size() - 1) + "}", source.length(), source.length()));
        }
        Matcher residual = PLACEHOLDER.matcher(source);
        while (residual.find()) {
            issues.add(new LatexLintIssue(Severity.BLOCKER, "RESIDUAL_PLACEHOLDER", "Residual placeholder after unmask", residual.start(), residual.end()));
        }
        return issues;
    }

    private Set<String> placeholdersIn(String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private List<Span> collectSpans(String source) {
        List<Span> candidates = new ArrayList<>();
        add(candidates, "ENV", ENV, source);
        add(candidates, "CITE", CITE, source);
        add(candidates, "REF", REF, source);
        add(candidates, "LABEL", LABEL, source);
        add(candidates, "GRAPHICS", GRAPHICS, source);
        add(candidates, "MATH", DISPLAY_MATH, source);
        add(candidates, "MATH", INLINE_MATH, source);
        candidates.sort(Comparator.comparingInt(Span::start).thenComparing((a, b) -> Integer.compare(b.end(), a.end())));
        List<Span> selected = new ArrayList<>();
        int cursor = -1;
        for (Span candidate : candidates) {
            if (candidate.start() >= cursor) {
                selected.add(candidate);
                cursor = candidate.end();
            }
        }
        return selected;
    }

    private void add(List<Span> spans, String kind, Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            spans.add(new Span(kind, matcher.start(), matcher.end()));
        }
    }

    public record PlaceholderValidation(boolean valid, Set<String> missing, Set<String> unexpected) {
    }

    private record Span(String kind, int start, int end) {
    }
}
