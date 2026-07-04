package com.yanban.paper.latex;

import java.util.List;

public record StructureClarificationCandidate(
        String type,
        boolean blocking,
        String message,
        List<String> options,
        String defaultOption,
        int relatedSectionOrderIndex
) {
}
