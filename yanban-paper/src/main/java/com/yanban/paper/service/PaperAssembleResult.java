package com.yanban.paper.service;

import java.util.List;
import java.util.Map;

public record PaperAssembleResult(
        Long taskId,
        boolean advancedMode,
        String polishedTex,
        String suggestedBib,
        String reviewReport,
        List<Map<String, Object>> artifacts,
        List<String> lintCodes
) {
}
