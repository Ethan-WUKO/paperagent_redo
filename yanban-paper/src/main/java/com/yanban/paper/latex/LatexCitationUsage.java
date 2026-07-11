package com.yanban.paper.latex;

import java.util.List;

public record LatexCitationUsage(
        String command,
        List<String> keys,
        int startOffset,
        int endOffset
) {
}
