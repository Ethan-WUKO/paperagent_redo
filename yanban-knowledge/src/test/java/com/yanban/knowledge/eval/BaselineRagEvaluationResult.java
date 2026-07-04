package com.yanban.knowledge.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BaselineRagEvaluationResult(
        String runner,
        Instant createdAt,
        Summary summary,
        List<CaseResult> cases
) {
    public record Summary(
            int totalCases,
            int passedCases,
            int failedCases,
            double recallAt5,
            double meanReciprocalRank,
            int forbiddenHitCount,
            double metadataPreservationRate
    ) {
    }

    public record CaseResult(
            String caseId,
            String area,
            boolean passed,
            List<Long> retrievedDocumentIds,
            List<String> retrievedCitationIds,
            List<Long> missingExpectedDocumentIds,
            List<Long> forbiddenDocumentIdsHit,
            List<String> missingExpectedCitationIds,
            Map<String, Boolean> rankingRuleResults,
            double reciprocalRank
    ) {
    }
}
