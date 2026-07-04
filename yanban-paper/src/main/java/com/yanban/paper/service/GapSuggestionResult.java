package com.yanban.paper.service;

import java.util.List;
import java.util.Map;

public record GapSuggestionResult(
        Long suggestionId,
        String track,
        String category,
        String severity,
        String statement,
        List<Long> evidenceCardIds,
        boolean applicable,
        Map<String, Object> patch
) {
}
