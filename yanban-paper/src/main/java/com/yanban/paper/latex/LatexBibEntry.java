package com.yanban.paper.latex;

import java.util.Map;

public record LatexBibEntry(
        String key,
        String type,
        Map<String, String> fields,
        String rawText,
        String source
) {
}
