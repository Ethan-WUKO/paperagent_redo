package com.yanban.paper.latex;

public record LatexSection(
        int orderIndex,
        int level,
        String command,
        boolean numbered,
        String title,
        LatexSectionRole role,
        int startOffset,
        int endOffset,
        String rawText
) {
}
