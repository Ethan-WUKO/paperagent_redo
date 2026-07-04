package com.yanban.paper.latex;

import java.util.List;

public record LatexFloat(
        String kind,
        String label,
        String caption,
        List<String> graphics,
        List<String> referencedBy,
        int startOffset,
        int endOffset,
        String rawContent
) {
}
