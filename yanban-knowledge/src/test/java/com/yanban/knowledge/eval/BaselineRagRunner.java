package com.yanban.knowledge.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BaselineRagRunner {

    private final BaselineSearchBackend searchBackend;
    private final String runnerName;

    public BaselineRagRunner(BaselineSearchBackend searchBackend) {
        this("current-rag-baseline", searchBackend);
    }

    public BaselineRagRunner(String runnerName, BaselineSearchBackend searchBackend) {
        this.runnerName = runnerName;
        this.searchBackend = searchBackend;
    }

    public BaselineRagEvaluationResult run(List<RagSpikeEvalCase> cases) {
        List<BaselineRagEvaluationResult.CaseResult> results = new ArrayList<>();
        for (RagSpikeEvalCase evalCase : cases) {
            results.add(evaluateCase(evalCase, searchBackend.search(evalCase)));
        }
        return new BaselineRagEvaluationResult(
                runnerName,
                Instant.now(),
                summarize(results),
                results
        );
    }

    public void writeJson(BaselineRagEvaluationResult result, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outputPath.toFile(), result);
    }

    private BaselineRagEvaluationResult.CaseResult evaluateCase(RagSpikeEvalCase evalCase, List<BaselineRagHit> hits) {
        List<Long> retrievedDocumentIds = hits.stream()
                .map(BaselineRagHit::documentId)
                .toList();
        List<String> retrievedCitationIds = hits.stream()
                .map(BaselineRagHit::citationId)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        List<Long> missingExpectedDocumentIds = missing(evalCase.expectedDocumentIds(), retrievedDocumentIds);
        List<Long> forbiddenDocumentIdsHit = intersection(evalCase.forbiddenDocumentIds(), retrievedDocumentIds);
        List<String> missingExpectedCitationIds = missing(evalCase.expectedCitationIds(), retrievedCitationIds);
        Map<String, Boolean> rankingRuleResults = evaluateRankingRules(evalCase, retrievedDocumentIds);
        double reciprocalRank = reciprocalRank(evalCase.expectedDocumentIds(), retrievedDocumentIds);
        boolean passed = missingExpectedDocumentIds.isEmpty()
                && forbiddenDocumentIdsHit.isEmpty()
                && missingExpectedCitationIds.isEmpty()
                && rankingRuleResults.values().stream().allMatch(Boolean::booleanValue);
        return new BaselineRagEvaluationResult.CaseResult(
                evalCase.caseId(),
                evalCase.area(),
                passed,
                retrievedDocumentIds,
                retrievedCitationIds,
                missingExpectedDocumentIds,
                forbiddenDocumentIdsHit,
                missingExpectedCitationIds,
                rankingRuleResults,
                reciprocalRank
        );
    }

    private BaselineRagEvaluationResult.Summary summarize(List<BaselineRagEvaluationResult.CaseResult> results) {
        int total = results.size();
        int passed = (int) results.stream().filter(BaselineRagEvaluationResult.CaseResult::passed).count();
        int forbiddenHits = results.stream()
                .mapToInt(result -> result.forbiddenDocumentIdsHit().size())
                .sum();
        long metadataChecked = results.stream()
                .flatMap(result -> result.retrievedCitationIds().stream())
                .count();
        long metadataPresent = results.stream()
                .flatMap(result -> result.retrievedCitationIds().stream())
                .filter(value -> value != null && !value.isBlank())
                .count();
        double recallAt5 = total == 0 ? 0.0d : results.stream()
                .mapToDouble(result -> result.missingExpectedDocumentIds().isEmpty() ? 1.0d : 0.0d)
                .average()
                .orElse(0.0d);
        double mrr = results.stream()
                .mapToDouble(BaselineRagEvaluationResult.CaseResult::reciprocalRank)
                .average()
                .orElse(0.0d);
        double metadataRate = metadataChecked == 0 ? 1.0d : metadataPresent / (double) metadataChecked;
        return new BaselineRagEvaluationResult.Summary(
                total,
                passed,
                total - passed,
                recallAt5,
                mrr,
                forbiddenHits,
                metadataRate
        );
    }

    private <T> List<T> missing(List<T> expected, List<T> actual) {
        Set<T> actualSet = new LinkedHashSet<>(actual == null ? List.of() : actual);
        List<T> result = new ArrayList<>();
        for (T item : expected == null ? List.<T>of() : expected) {
            if (!actualSet.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private <T> List<T> intersection(List<T> expected, List<T> actual) {
        Set<T> actualSet = new LinkedHashSet<>(actual == null ? List.of() : actual);
        List<T> result = new ArrayList<>();
        for (T item : expected == null ? List.<T>of() : expected) {
            if (actualSet.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private Map<String, Boolean> evaluateRankingRules(RagSpikeEvalCase evalCase, List<Long> retrievedDocumentIds) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (evalCase.rankingRules() == null) {
            return results;
        }
        for (RagSpikeEvalCase.RankingRule rule : evalCase.rankingRules()) {
            int preferredIndex = retrievedDocumentIds.indexOf(rule.preferredDocumentId());
            int lowerIndex = retrievedDocumentIds.indexOf(rule.lowerPriorityDocumentId());
            String key = rule.preferredDocumentId() + "_before_" + rule.lowerPriorityDocumentId();
            results.put(key, preferredIndex >= 0 && (lowerIndex < 0 || preferredIndex < lowerIndex));
        }
        return results;
    }

    private double reciprocalRank(List<Long> expectedDocumentIds, List<Long> retrievedDocumentIds) {
        if (expectedDocumentIds == null || expectedDocumentIds.isEmpty()) {
            return 0.0d;
        }
        for (int i = 0; i < retrievedDocumentIds.size(); i++) {
            if (expectedDocumentIds.contains(retrievedDocumentIds.get(i))) {
                return 1.0d / (i + 1);
            }
        }
        return 0.0d;
    }
}
