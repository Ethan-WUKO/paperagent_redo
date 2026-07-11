package com.yanban.paper.latex;

public record LatexProtectedSpan(
        String id,
        String kind,
        int startOffset,
        int endOffset,
        String rawText
) {
}
