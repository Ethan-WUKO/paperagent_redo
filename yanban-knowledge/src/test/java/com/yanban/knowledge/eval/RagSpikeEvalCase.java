package com.yanban.knowledge.eval;

import java.util.List;

public record RagSpikeEvalCase(
        String caseId,
        String area,
        String previousContext,
        String query,
        Long userId,
        Long projectId,
        int topK,
        List<Long> expectedDocumentIds,
        List<Long> forbiddenDocumentIds,
        List<String> expectedCitationIds,
        List<String> expectedAnswerFacts,
        List<RankingRule> rankingRules,
        String notes
) {
    public record RankingRule(
            Long preferredDocumentId,
            Long lowerPriorityDocumentId,
            String reason
    ) {
    }
}
