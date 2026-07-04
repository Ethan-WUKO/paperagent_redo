package com.yanban.paper.latex;

public record LatexLintIssue(
        Severity severity,
        String code,
        String message,
        int startOffset,
        int endOffset
) {
    public enum Severity {
        BLOCKER,
        MINOR
    }
}
