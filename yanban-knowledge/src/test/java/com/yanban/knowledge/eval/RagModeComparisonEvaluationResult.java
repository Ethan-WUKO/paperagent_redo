package com.yanban.knowledge.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RagModeComparisonEvaluationResult(
        String suite,
        Instant createdAt,
        Summary summary,
        List<BaselineRagEvaluationResult> modeResults,
        List<CaseComparison> caseComparisons
) {

    static RagModeComparisonEvaluationResult from(String suite, List<BaselineRagEvaluationResult> modeResults) {
        BaselineRagEvaluationResult bestRecall = modeResults.stream()
                .max(java.util.Comparator.comparingDouble(item -> item.summary().recallAt5()))
                .orElseThrow();
        BaselineRagEvaluationResult bestMrr = modeResults.stream()
                .max(java.util.Comparator.comparingDouble(item -> item.summary().meanReciprocalRank()))
                .orElseThrow();

        Map<String, List<ModeCaseSnapshot>> cases = new LinkedHashMap<>();
        Map<String, String> areaByCaseId = new LinkedHashMap<>();
        for (BaselineRagEvaluationResult modeResult : modeResults) {
            for (BaselineRagEvaluationResult.CaseResult caseResult : modeResult.cases()) {
                areaByCaseId.putIfAbsent(caseResult.caseId(), caseResult.area());
                cases.computeIfAbsent(caseResult.caseId(), ignored -> new ArrayList<>())
                        .add(new ModeCaseSnapshot(
                                modeResult.runner(),
                                caseResult.passed(),
                                caseResult.reciprocalRank(),
                                caseResult.missingExpectedDocumentIds(),
                                caseResult.forbiddenDocumentIdsHit(),
                                caseResult.missingExpectedCitationIds()
                        ));
            }
        }

        List<CaseComparison> caseComparisons = cases.entrySet().stream()
                .map(entry -> new CaseComparison(
                        entry.getKey(),
                        areaByCaseId.get(entry.getKey()),
                        entry.getValue(),
                        allSame(entry.getValue())
                ))
                .toList();

        int totalCases = modeResults.isEmpty() ? 0 : modeResults.get(0).summary().totalCases();
        return new RagModeComparisonEvaluationResult(
                suite,
                Instant.now(),
                new Summary(
                        modeResults.size(),
                        totalCases,
                        bestRecall.runner(),
                        bestRecall.summary().recallAt5(),
                        bestMrr.runner(),
                        bestMrr.summary().meanReciprocalRank()
                ),
                modeResults,
                caseComparisons
        );
    }

    private static boolean allSame(List<ModeCaseSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return true;
        }
        ModeCaseSnapshot first = snapshots.get(0);
        for (int i = 1; i < snapshots.size(); i++) {
            ModeCaseSnapshot current = snapshots.get(i);
            if (first.passed() != current.passed()
                    || Double.compare(first.reciprocalRank(), current.reciprocalRank()) != 0
                    || !first.missingExpectedDocumentIds().equals(current.missingExpectedDocumentIds())
                    || !first.forbiddenDocumentIdsHit().equals(current.forbiddenDocumentIdsHit())
                    || !first.missingExpectedCitationIds().equals(current.missingExpectedCitationIds())) {
                return false;
            }
        }
        return true;
    }

    public record Summary(
            int totalModes,
            int totalCases,
            String bestRecallRunner,
            double bestRecallAt5,
            String bestMrrRunner,
            double bestMrr
    ) {
    }

    public record CaseComparison(
            String caseId,
            String area,
            List<ModeCaseSnapshot> modes,
            boolean identicalAcrossModes
    ) {
    }

    public record ModeCaseSnapshot(
            String runner,
            boolean passed,
            double reciprocalRank,
            List<Long> missingExpectedDocumentIds,
            List<Long> forbiddenDocumentIdsHit,
            List<String> missingExpectedCitationIds
    ) {
    }
}
