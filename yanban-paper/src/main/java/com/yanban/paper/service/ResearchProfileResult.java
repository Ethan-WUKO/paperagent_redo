package com.yanban.paper.service;

import java.util.List;

public record ResearchProfileResult(
        String problem,
        String method,
        List<String> contributions,
        List<String> datasets,
        List<String> baselines,
        List<String> metrics,
        List<String> tasks,
        List<String> keywords,
        boolean degraded,
        String rawText
) {
}
