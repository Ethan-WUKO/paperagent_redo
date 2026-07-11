package com.yanban.paper.service;

import com.yanban.paper.latex.LatexLintIssue;
import java.util.List;
import java.util.Map;

public record SectionPolishResult(
        Long sectionId,
        String status,
        String originalText,
        String polishedText,
        double reviewScore,
        boolean passed,
        int attempts,
        Map<String, Object> review,
        Map<String, Object> diff,
        List<LatexLintIssue> lintIssues
) {
}
