package com.yanban.paper.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexLintIssue;
import com.yanban.paper.latex.LatexMaskingService;
import com.yanban.paper.latex.LatexParserService;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.LatexSectionRole;
import com.yanban.paper.service.PaperModelClient;
import com.yanban.paper.service.PaperPromptService;
import com.yanban.paper.service.PaperSectionPolishService;
import com.yanban.paper.service.PaperStorageService;
import com.yanban.paper.service.SectionPolishResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = PaperPolishBaselineEvaluationTest.TestConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PaperPolishBaselineEvaluationTest {

    private static final Pattern STRUCTURAL_COMMAND = Pattern.compile(
            "\\\\(?:cite|citep|citet|citeauthor|citeyear|parencite|textcite|autocite|ref|eqref|cref|Cref|autoref|pageref|label|includegraphics|bibliography|section|subsection|subsubsection|paragraph|subparagraph)\\*?(?:\\s*\\[[^]]*]){0,2}\\s*\\{([^{}]+)}"
                    + "|\\\\(?:begin|end)\\s*\\{(?:equation|equation\\*|align|align\\*|aligned|figure|figure\\*|table|table\\*|algorithm|algorithmic|enumerate|itemize|cases|split|gather|gather\\*|multline|multline\\*|IEEEeqnarray)\\}");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {PaperTask.class, PaperSection.class, PaperTaskAnalysis.class})
    @EnableJpaRepositories(basePackageClasses = {PaperTaskRepository.class, PaperSectionRepository.class, PaperTaskAnalysisRepository.class})
    @Import({PaperSectionPolishService.class, PaperPromptService.class, LatexMaskingService.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        }

        @Bean
        BaselineModelClient paperModelClient() {
            return new BaselineModelClient();
        }

        @Bean
        PaperStorageService paperStorageService() {
            return new PaperStorageService(new EmptyMinioProvider(), new com.yanban.paper.config.PaperStorageProperties()) {
                @Override
                public String storeArtifact(Long userId, String type, String filename, byte[] bytes, String contentType) {
                    return "eval://" + type + "/" + filename;
                }
            };
        }
    }

    private final PaperSectionPolishService polishService;
    private final PaperTaskRepository tasks;
    private final PaperSectionRepository sections;
    private final PaperTaskAnalysisRepository analyses;
    private final BaselineModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final LatexParserService parser = new LatexParserService();
    private final LatexMaskingService maskingService = new LatexMaskingService();

    @Autowired
    PaperPolishBaselineEvaluationTest(PaperSectionPolishService polishService,
                                      PaperTaskRepository tasks,
                                      PaperSectionRepository sections,
                                      PaperTaskAnalysisRepository analyses,
                                      BaselineModelClient modelClient,
                                      ObjectMapper objectMapper) {
        this.polishService = polishService;
        this.tasks = tasks;
        this.sections = sections;
        this.analyses = analyses;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void resetModel() {
        modelClient.reset();
    }

    @Test
    void evaluatePaperPolishBaselineCases() throws Exception {
        List<EvalCase> cases = evalCases();
        List<CaseReport> reports = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            modelClient.mode = evalCase.mode();
            SectionPolishResult result = runCase(evalCase);
            CaseReport report = score(evalCase, result);
            reports.add(report);

            assertThat(report.status()).isEqualTo(evalCase.expectedStatus());
            assertThat(report.structuralCommandsPreserved()).isTrue();
            assertThat(report.noBlockerLint()).isTrue();
            assertThat(report.protectedTokenChecks()).allSatisfy((token, present) -> assertThat(present).as(token).isTrue());
            if (evalCase.mode() == ModelMode.UNSAFE_DROP_PLACEHOLDER) {
                assertThat(report.keptOriginalWhenUnsafe()).isTrue();
            }
        }
        writeReports(new EvalReport(Instant.now().toString(), statusCounts(reports), reports));
    }

    private SectionPolishResult runCase(EvalCase evalCase) {
        PaperTask task = tasks.save(new PaperTask(1L, evalCase.paperTitle(), "main.tex", "paper/main.tex", "RUNNING", evalCase.targetLanguage(), "POLISH", null));
        PaperTaskAnalysis analysis = new PaperTaskAnalysis(task.getId());
        analysis.setResearchProfileJson("{\"topic\":\"paper polish baseline eval\"}");
        analysis.setConceptLadderJson("{\"citationAudit\":\"fixed fixture only\"}");
        analyses.save(analysis);
        sections.save(new PaperSection(task.getId(), "main.tex", evalCase.section().orderIndex(), evalCase.section().level(),
                evalCase.section().title(), evalCase.section().role().name(), 1.0, "eval",
                evalCase.section().startOffset(), evalCase.section().endOffset()));
        return polishService.polishSection(task.getId(), evalCase.section(), evalCase.targetLanguage(), 80, evalCase.maxAttempts());
    }

    private CaseReport score(EvalCase evalCase, SectionPolishResult result) {
        boolean structuralPreserved = structuralCommands(result.originalText()).equals(structuralCommands(result.polishedText()));
        boolean noBlockerLint = maskingService.lint(result.polishedText()).stream()
                .noneMatch(issue -> issue.severity() == LatexLintIssue.Severity.BLOCKER);
        Map<String, Boolean> tokenChecks = new LinkedHashMap<>();
        for (String token : evalCase.requiredTokens()) {
            tokenChecks.put(token, result.polishedText().contains(token));
        }
        Map<String, Object> diff = result.diff() == null ? Map.of() : result.diff();
        int lengthDelta = number(diff.get("lengthDelta"), result.polishedText().length() - result.originalText().length());
        boolean changed = booleanValue(diff.get("changed"), !result.originalText().equals(result.polishedText()));
        return new CaseReport(
                evalCase.id(),
                evalCase.sample(),
                result.status(),
                result.passed(),
                result.reviewScore(),
                result.attempts(),
                changed,
                lengthDelta,
                structuralPreserved,
                noBlockerLint,
                result.originalText().equals(result.polishedText()),
                tokenChecks,
                result.review()
        );
    }

    private List<EvalCase> evalCases() throws IOException {
        LatexDocument english = parser.parse(
                "main.tex",
                read(sampleRoot("en-literature-gap").resolve("main.tex")),
                Map.of("refs.bib", read(sampleRoot("en-literature-gap").resolve("refs.bib")))
        );
        LatexSection englishIntro = english.sections().stream()
                .filter(section -> section.title().equals("Introduction"))
                .findFirst()
                .orElseThrow();

        LatexDocument chinese = parser.parse(
                "main.tex",
                read(sampleRoot("zh-rag-polish").resolve("main.tex")),
                Map.of("refs.bib", read(sampleRoot("zh-rag-polish").resolve("refs.bib")))
        );
        LatexSection chineseIntro = chinese.sections().stream()
                .filter(section -> section.rawText().contains("\\cite{"))
                .findFirst()
                .orElseThrow();
        LatexSection chineseFigureMethod = chinese.sections().stream()
                .filter(section -> section.rawText().contains("\\includegraphics"))
                .findFirst()
                .orElseThrow();
        String chineseProtectedText = chineseIntro.rawText() + "\n" + chineseFigureMethod.rawText();
        LatexSection chineseProtected = new LatexSection(
                chineseIntro.orderIndex(),
                2,
                "section",
                true,
                "Chinese protected-span baseline",
                LatexSectionRole.METHOD,
                chineseIntro.startOffset(),
                chineseFigureMethod.endOffset(),
                chineseProtectedText
        );

        return List.of(
                new EvalCase("POLISH-EN-001", "en-literature-gap/main.tex", "Evidence-Grounded Literature Assistance",
                        englishIntro, "en", ModelMode.CONSERVATIVE_POLISH, "POLISHED", 1,
                        List.of("\\section{Introduction}", "\\cite{lewis2020rag}", "\\ref{eq:score}")),
                new EvalCase("POLISH-ZH-001", "zh-rag-polish/main.tex", "Chinese RAG sample",
                        chineseProtected, "zh", ModelMode.CONSERVATIVE_POLISH, "POLISHED", 1,
                        List.of("\\cite{lewis2020rag}", "\\ref{fig:pipeline}", "\\includegraphics", "\\label{fig:pipeline}", "$q$", "Top-$k$")),
                new EvalCase("POLISH-NEG-001", "en-literature-gap/main.tex", "Unsafe placeholder regression",
                        englishIntro, "en", ModelMode.UNSAFE_DROP_PLACEHOLDER, "PROTECTION_REJECTED", 1,
                        List.of("\\section{Introduction}", "\\cite{lewis2020rag}", "\\ref{eq:score}"))
        );
    }

    private void writeReports(EvalReport report) throws IOException {
        Path outputDir = Path.of("target", "paper-polish-baseline-eval");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("report.json"), objectMapper.writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("report.md"), markdown(report), StandardCharsets.UTF_8);
    }

    private String markdown(EvalReport report) {
        StringBuilder md = new StringBuilder();
        md.append("# Paper Polish Baseline Evaluation\n\n");
        md.append("- Generated at: ").append(report.generatedAt()).append("\n");
        md.append("- Case count: ").append(report.cases().size()).append("\n");
        md.append("- Status counts: ").append(report.statusCounts()).append("\n");
        md.append("- Purpose: first baseline for must-not-regress and obvious-drift checks, not full academic quality grading.\n\n");
        md.append("## Summary\n\n");
        md.append("| Case | Sample | Status | Passed | Review score | Attempts | Changed | Length delta | Structure | Lint |\n");
        md.append("|---|---|---|---:|---:|---:|---:|---:|---|---|\n");
        for (CaseReport item : report.cases()) {
            md.append("| ").append(item.id())
                    .append(" | ").append(item.sample())
                    .append(" | ").append(item.status())
                    .append(" | ").append(item.passed())
                    .append(" | ").append(item.reviewScore())
                    .append(" | ").append(item.attempts())
                    .append(" | ").append(item.changed())
                    .append(" | ").append(item.lengthDelta())
                    .append(" | ").append(passFail(item.structuralCommandsPreserved()))
                    .append(" | ").append(passFail(item.noBlockerLint()))
                    .append(" |\n");
        }
        md.append("\n## Mandatory Checks\n\n");
        for (CaseReport item : report.cases()) {
            md.append("### ").append(item.id()).append("\n\n");
            md.append("- Status: ").append(item.status()).append("\n");
            md.append("- Structural commands preserved: ").append(passFail(item.structuralCommandsPreserved())).append("\n");
            md.append("- No blocker LaTeX lint: ").append(passFail(item.noBlockerLint())).append("\n");
            md.append("- Unsafe output kept original: ").append(item.keptOriginalWhenUnsafe()).append("\n");
            md.append("- Required tokens:\n");
            item.protectedTokenChecks().forEach((token, present) ->
                    md.append("  - `").append(token.replace("`", "\\`")).append("`: ").append(passFail(present)).append("\n"));
            md.append("\n");
        }
        md.append("## Manual Inspection Boundary\n\n");
        md.append("This eval does not judge all scholarly quality. Human review is still needed for contribution novelty, citation suitability, discipline-specific rhetoric, and final publication readiness.\n");
        return md.toString();
    }

    private static Map<String, Long> statusCounts(List<CaseReport> reports) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CaseReport report : reports) {
            counts.put(report.status(), counts.getOrDefault(report.status(), 0L) + 1);
        }
        return counts;
    }

    private static List<String> structuralCommands(String text) {
        List<String> commands = new ArrayList<>();
        Matcher matcher = STRUCTURAL_COMMAND.matcher(text == null ? "" : text);
        while (matcher.find()) {
            commands.add(matcher.group().replaceAll("\\s+", " ").trim());
        }
        return commands;
    }

    private static Path sampleRoot(String name) {
        return Path.of("src/test/resources/paper-quality-samples", name);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String passFail(boolean value) {
        return value ? "PASS" : "FAIL";
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private enum ModelMode {
        CONSERVATIVE_POLISH,
        UNSAFE_DROP_PLACEHOLDER
    }

    private record EvalCase(String id,
                            String sample,
                            String paperTitle,
                            LatexSection section,
                            String targetLanguage,
                            ModelMode mode,
                            String expectedStatus,
                            int maxAttempts,
                            List<String> requiredTokens) {
    }

    private record EvalReport(String generatedAt, Map<String, Long> statusCounts, List<CaseReport> cases) {
    }

    private record CaseReport(String id,
                              String sample,
                              String status,
                              boolean passed,
                              double reviewScore,
                              int attempts,
                              boolean changed,
                              int lengthDelta,
                              boolean structuralCommandsPreserved,
                              boolean noBlockerLint,
                              boolean keptOriginalWhenUnsafe,
                              Map<String, Boolean> protectedTokenChecks,
                              Map<String, Object> review) {
    }

    static final class EmptyMinioProvider implements ObjectProvider<io.minio.MinioClient> {
        @Override
        public io.minio.MinioClient getObject(Object... args) {
            return null;
        }

        @Override
        public io.minio.MinioClient getIfAvailable() {
            return null;
        }

        @Override
        public io.minio.MinioClient getIfUnique() {
            return null;
        }

        @Override
        public io.minio.MinioClient getObject() {
            return null;
        }
    }

    static final class BaselineModelClient implements PaperModelClient {
        private ModelMode mode = ModelMode.CONSERVATIVE_POLISH;

        void reset() {
            mode = ModelMode.CONSERVATIVE_POLISH;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, Double temperature, Integer maxTokens) {
            if (systemPrompt != null && systemPrompt.contains("strict JSON")) {
                return """
                        {
                          "score": 86,
                          "passed": true,
                          "issues": [],
                          "suggestions": ["Baseline deterministic review passed."],
                          "preservesOriginalMeaning": true,
                          "introducedUnsupportedContent": false,
                          "needsRepair": false
                        }
                        """;
            }
            if (systemPrompt != null && systemPrompt.contains("Repair conservatively")) {
                return "<output>" + currentPolishedSection(userPrompt) + "</output><explanation>no repair needed</explanation>";
            }
            if (mode == ModelMode.UNSAFE_DROP_PLACEHOLDER) {
                return "<output>\\section{Introduction}\nThis unsafe rewrite drops protected citation and reference placeholders.</output><explanation>unsafe</explanation>";
            }
            String original = sectionText(userPrompt);
            return "<output>" + conservativePolish(original) + "</output><explanation>conservative baseline polish</explanation>";
        }

        private String conservativePolish(String text) {
            String trimmed = text == null ? "" : text.trim();
            String suffix = trimmedHasCjk(trimmed)
                    ? "\nThis baseline sentence is intentionally minimal and preserves the original scoped claim."
                    : "\nThis baseline revision keeps the claim scoped and preserves the original evidence trail.";
            return trimmed + suffix;
        }

        private boolean trimmedHasCjk(String text) {
            for (int i = 0; i < text.length(); i++) {
                if (Character.UnicodeBlock.of(text.charAt(i)) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                    return true;
                }
            }
            return false;
        }

        private String sectionText(String prompt) {
            return blockAfter(prompt, "Introduction text:", "Section text:");
        }

        private String currentPolishedSection(String prompt) {
            return blockAfter(prompt, "Current polished section text:");
        }

        private String blockAfter(String prompt, String... markers) {
            String source = prompt == null ? "" : prompt;
            int start = -1;
            for (String marker : markers) {
                start = source.indexOf(marker);
                if (start >= 0) {
                    start += marker.length();
                    break;
                }
            }
            if (start < 0) return "";
            int end = source.indexOf("\n\n<output>", start);
            if (end < 0) {
                end = source.length();
            }
            return source.substring(start, end).trim();
        }
    }
}
