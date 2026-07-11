package com.yanban.paper.latex;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record MaskedLatexText(
        String text,
        Map<String, String> placeholders,
        Set<String> placeholderSet,
        List<LatexLintIssue> lintIssues
) {
}
