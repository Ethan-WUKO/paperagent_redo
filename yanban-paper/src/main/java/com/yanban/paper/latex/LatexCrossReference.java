package com.yanban.paper.latex;

public record LatexCrossReference(
        String command,
        String label,
        int startOffset,
        int endOffset
) {
}
