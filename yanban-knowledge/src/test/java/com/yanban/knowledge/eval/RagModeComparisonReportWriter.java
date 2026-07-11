package com.yanban.knowledge.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RagModeComparisonReportWriter {

    private RagModeComparisonReportWriter() {
    }

    public static void writeJson(RagModeComparisonEvaluationResult result, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outputPath.toFile(), result);
    }

    public static void writeMarkdown(RagModeComparisonEvaluationResult result, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, toMarkdown(result));
    }

    private static String toMarkdown(RagModeComparisonEvaluationResult result) {
        List<String> lines = new ArrayList<>();
        lines.add("# RAG Mode Comparison");
        lines.add("");
        lines.add("Suite: `" + result.suite() + "`");
        lines.add("");
        lines.add("Generated at: `" + result.createdAt() + "`");
        lines.add("");
        lines.add("Best Recall@5: `" + result.summary().bestRecallRunner() + "` = `"
                + format(result.summary().bestRecallAt5()) + "`");
        lines.add("");
        lines.add("Best MRR: `" + result.summary().bestMrrRunner() + "` = `"
                + format(result.summary().bestMrr()) + "`");
        lines.add("");
        lines.add("## Summary");
        lines.add("");
        lines.add("| Runner | Passed | Failed | Recall@5 | MRR | Forbidden Hits | Metadata Rate |");
        lines.add("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");
        for (BaselineRagEvaluationResult modeResult : result.modeResults()) {
            BaselineRagEvaluationResult.Summary summary = modeResult.summary();
            lines.add("| `" + modeResult.runner() + "` | "
                    + summary.passedCases() + " | "
                    + summary.failedCases() + " | "
                    + format(summary.recallAt5()) + " | "
                    + format(summary.meanReciprocalRank()) + " | "
                    + summary.forbiddenHitCount() + " | "
                    + format(summary.metadataPreservationRate()) + " |");
        }
        lines.add("");
        lines.add("## Case Differences");
        lines.add("");
        List<RagModeComparisonEvaluationResult.CaseComparison> differences = result.caseComparisons().stream()
                .filter(item -> !item.identicalAcrossModes())
                .toList();
        if (differences.isEmpty()) {
            lines.add("All recorded cases produced identical retrieval outcomes across the compared modes.");
            lines.add("");
            return String.join("\n", lines);
        }
        for (RagModeComparisonEvaluationResult.CaseComparison comparison : differences) {
            lines.add("### `" + comparison.caseId() + "`");
            lines.add("");
            lines.add("Area: `" + comparison.area() + "`");
            lines.add("");
            lines.add("| Runner | Passed | Reciprocal Rank | Missing Expected Docs | Forbidden Hits | Missing Citations |");
            lines.add("| --- | ---: | ---: | --- | --- | --- |");
            for (RagModeComparisonEvaluationResult.ModeCaseSnapshot snapshot : comparison.modes()) {
                lines.add("| `" + snapshot.runner() + "` | "
                        + snapshot.passed() + " | "
                        + format(snapshot.reciprocalRank()) + " | "
                        + snapshot.missingExpectedDocumentIds() + " | "
                        + snapshot.forbiddenDocumentIdsHit() + " | "
                        + snapshot.missingExpectedCitationIds() + " |");
            }
            lines.add("");
        }
        return String.join("\n", lines);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
