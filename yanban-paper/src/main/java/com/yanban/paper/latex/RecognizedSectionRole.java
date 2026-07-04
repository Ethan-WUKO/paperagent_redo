package com.yanban.paper.latex;

public record RecognizedSectionRole(
        int sectionOrderIndex,
        String title,
        LatexSectionRole role,
        double confidence,
        String source
) {
}
