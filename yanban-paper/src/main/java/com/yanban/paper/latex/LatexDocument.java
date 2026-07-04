package com.yanban.paper.latex;

import java.util.List;
import java.util.Map;

public record LatexDocument(
        String sourcePath,
        String title,
        List<String> authors,
        List<String> keywords,
        String preamble,
        String frontMatter,
        List<LatexSection> sections,
        List<LatexProtectedSpan> protectedSpans,
        List<LatexFloat> floats,
        List<LatexCitationUsage> citationUsages,
        List<LatexCrossReference> crossReferences,
        Map<String, LatexBibEntry> bibliography,
        List<LatexLintIssue> lintIssues
) {
}
