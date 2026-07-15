package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.LocalServerProjectRootProvider;
import com.yanban.api.project.Project;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectRepository;
import com.yanban.api.project.ProjectService;
import com.yanban.api.project.ProjectStorageProperties;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolExecutionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises executors through the real ProjectService path/ownership boundary, not mocked reads. */
class ResearchProjectToolExecutorTest {
    @TempDir Path tempDir;
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectStorageProperties properties = new ProjectStorageProperties();
    private final ProjectRepository repository = org.mockito.Mockito.mock(ProjectRepository.class);

    @AfterEach void clearContext() { ToolExecutionContext.clear(); }

    @Test
    void extractsFirstVersionTypedResearchItemsWithProvenance() throws Exception {
        ProjectService service = serviceWithFixture(); authorizeAll();
        assertComplete(new ProjectLatexOutlineToolExecutor(service, json), "project_latex_outline",
                paths("paper.tex"));
        assertComplete(new ProjectBibtexAuditToolExecutor(service, json), "project_bibtex_audit",
                paths("refs.bib", "paper.tex"));
        assertComplete(new ProjectCodeSymbolsToolExecutor(service, json), "project_code_symbols",
                paths("Main.java", "run.py", "fit.m"));
        assertComplete(new ProjectExperimentSummaryToolExecutor(service, json), "project_experiment_summary",
                paths("results.csv", "config.yaml", "report.txt", "metrics.json"));
        assertComplete(new ProjectCrossMaterialSearchToolExecutor(service, json), "project_cross_material_search",
                queryPaths("learning_rate", "paper.tex", "config.yaml"));
    }

    @Test
    void failsClosedForMissingTrustedScopeDeniedAllowListAndUnsafeArguments() throws Exception {
        ProjectService service = serviceWithFixture();
        ProjectLatexOutlineToolExecutor executor = new ProjectLatexOutlineToolExecutor(service, json);
        var arguments = json.createObjectNode().putArray("relativePaths").add("paper.tex");
        assertThat(executor.execute(new ToolCall("a", "project_latex_outline", arguments)).success()).isFalse();
        ToolExecutionContext.setCurrentUserId(7L); ToolExecutionContext.setCurrentProjectId(42L);
        ToolExecutionContext.setResolvedAllowedTools(Set.of());
        assertThat(executor.execute(new ToolCall("b", "project_latex_outline", arguments)).errorCode().name()).isEqualTo("PERMISSION_DENIED");
        ToolExecutionContext.setResolvedAllowedTools(Set.of("project_latex_outline"));
        var unsafe = json.createObjectNode().putArray("relativePaths").add("../secret.tex");
        assertThat(executor.execute(new ToolCall("c", "project_latex_outline", unsafe)).errorCode().name()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void canonicalizesOneModelSuppliedRelativePathBeforeFrozenContractValidation() throws Exception {
        ProjectService service = serviceWithFixture(); authorizeAll();
        ProjectLatexOutlineToolExecutor executor = new ProjectLatexOutlineToolExecutor(service, json);
        var scalar = json.createObjectNode().put("relativePaths", "paper.tex").put("includeFormulaReferences", true);
        var unsafeScalar = json.createObjectNode().put("relativePaths", "../secret.tex");

        var result = executor.execute(new ToolCall("scalar", "project_latex_outline", scalar));
        var unsafe = executor.execute(new ToolCall("unsafe-scalar", "project_latex_outline", unsafeScalar));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("items")).isNotEmpty();
        assertThat(unsafe.success()).isFalse();
        assertThat(unsafe.errorCode().name()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void latexOutlineTypedItemsPreserveEveryObservedSubsectionForDeterministicCounting() throws Exception {
        ProjectService service = serviceWithFixture();
        Files.writeString(tempDir.resolve("server/study/paper.tex"), """
                \\section{Problem}
                \\subsection{Joint Optimization}
                \\subsection{Hierarchical Solution}
                \\section{Simulation}
                \\subsection{Configuration}
                \\subsection{Scenarios}
                \\subsection{Complexity}
                """);
        authorizeAll();

        var result = new ProjectLatexOutlineToolExecutor(service, json).execute(
                new ToolCall("outline-count", "project_latex_outline", paths("paper.tex")));

        assertThat(result.success()).isTrue();
        var subsectionItems = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        result.output().path("items").forEach(item -> {
            if ("SECTION".equals(item.path("kind").asText())
                    && "subsection".equals(item.path("identifier").asText())) {
                subsectionItems.add(item);
            }
        });
        assertThat(subsectionItems).hasSize(5);
        assertThat(subsectionItems).extracting(item -> item.path("detail").asText())
                .containsExactly("Joint Optimization", "Hierarchical Solution", "Configuration", "Scenarios", "Complexity");
    }

    @Test
    void manifestThenReadHashChangeFailsClosedWithoutLeakingStaleEvidence() throws Exception {
        ProjectService real = serviceWithFixture();
        ProjectService service = org.mockito.Mockito.spy(real);
        org.mockito.Mockito.doAnswer(call -> {
            Object manifest = call.callRealMethod();
            Files.writeString(tempDir.resolve("server/study/paper.tex"), "\\section{Changed}\n");
            return manifest;
        }).when(service).manifest(7L, 42L);
        authorizeAll();

        var result = new ProjectLatexOutlineToolExecutor(service, json).execute(new ToolCall("toc", "project_latex_outline", paths("paper.tex")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode().name()).isEqualTo("CONFLICT");
        assertThat(result.output()).isNull();
        assertThat(result.errorMessage()).contains("INDEX_STALE");
    }

    @Test
    void manifestThenDeletionFailsCrossMaterialClosedWithoutSuccessEvidence() throws Exception {
        ProjectService real = serviceWithFixture(); ProjectService service = org.mockito.Mockito.spy(real);
        org.mockito.Mockito.doAnswer(call -> {
            Object manifest = call.callRealMethod(); Files.delete(tempDir.resolve("server/study/config.yaml")); return manifest;
        }).when(service).manifest(7L, 42L);
        authorizeAll();

        var result = new ProjectCrossMaterialSearchToolExecutor(service, json).execute(new ToolCall("deleted", "project_cross_material_search",
                queryPaths("learning_rate", "paper.tex", "config.yaml")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode().name()).isEqualTo("CONFLICT");
        assertThat(result.output()).isNull();
        assertThat(result.evidenceRefs()).isEmpty();
    }

    @Test
    void missingManifestPathIsValidationFailureButMixedPathsRemainObservablePartial() throws Exception {
        ProjectService service = serviceWithFixture(); authorizeAll();
        ProjectLatexOutlineToolExecutor executor = new ProjectLatexOutlineToolExecutor(service, json);
        var missing = executor.execute(new ToolCall("missing", "project_latex_outline", paths("gone.tex")));
        var mixed = executor.execute(new ToolCall("mixed", "project_latex_outline", paths("paper.tex", "unsupported.txt")));

        assertThat(missing.success()).isFalse();
        assertThat(missing.errorCode().name()).isEqualTo("VALIDATION_ERROR");
        assertThat(missing.output()).isNull();
        assertThat(mixed.success()).isTrue();
        assertThat(mixed.output().path("status").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void crossMaterialDefaultManifestScanSkipsBinaryFilesThroughProjectServiceBoundary() throws Exception {
        ProjectService service = serviceWithFixture(); authorizeAll();
        var result = new ProjectCrossMaterialSearchToolExecutor(service, json).execute(new ToolCall("cross", "project_cross_material_search",
                json.createObjectNode().put("query", "learning_rate")));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("status").asText()).isEqualTo("COMPLETE");
        assertThat(result.output().path("items").size()).isEqualTo(1);
        assertThat(result.output().path("items").get(0).path("linkedEvidence").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void crossMaterialSingleFileReturnsCaseInsensitiveLiteralMatchesWithEvidence() throws Exception {
        ProjectService service = serviceWithFixture(); authorizeAll();
        Files.writeString(tempDir.resolve("server/study/paper.tex"), "Objective one\nSINR objective two\n");
        var arguments = queryPaths("objective", "paper.tex").put("caseSensitive", false);

        var result = new ProjectCrossMaterialSearchToolExecutor(service, json).execute(
                new ToolCall("single", "project_cross_material_search", arguments));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("status").asText()).isEqualTo("COMPLETE");
        assertThat(result.output().path("items")).hasSize(2);
        assertThat(result.output().path("items").get(0).path("relativePath").asText()).isEqualTo("paper.tex");
        assertThat(result.output().path("items").get(0).path("lineNumber").asInt()).isEqualTo(1);
        assertThat(result.output().path("evidenceRefs")).hasSize(2);
    }

    @Test
    void crossMaterialMultiFileWithOnlyOneFileMatchingReturnsObservablePartial() throws Exception {
        ProjectService service = serviceWithFixture(); authorizeAll();

        var result = new ProjectCrossMaterialSearchToolExecutor(service, json).execute(
                new ToolCall("one-sided", "project_cross_material_search",
                        queryPaths("learning_rate", "paper.tex", "Main.java")));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("status").asText()).isEqualTo("PARTIAL");
        assertThat(result.output().path("items").get(0).path("relativePath").asText()).isEqualTo("paper.tex");
        assertThat(result.output().path("errorCode").asText()).isEqualTo("PARTIAL_RESULT");
    }

    @Test
    void largeProjectRequiresExplicitRelativePathsBeforeCrossMaterialSearch() {
        ProjectService service = org.mockito.Mockito.mock(ProjectService.class);
        when(service.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(
                42L,
                "a".repeat(64),
                List.of(
                        new ProjectFileEntry("paper.tex", 3_000_001L, Instant.EPOCH, "b".repeat(64)),
                        new ProjectFileEntry("code.py", 3_000_001L, Instant.EPOCH, "c".repeat(64))
                )));
        authorizeAll();

        var result = new ProjectCrossMaterialSearchToolExecutor(service, json).execute(new ToolCall(
                "large-cross",
                "project_cross_material_search",
                json.createObjectNode().put("query", "objective").put("maxMatches", 20)));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode().name()).isEqualTo("VALIDATION_ERROR");
        assertThat(result.errorMessage())
                .contains("requires relativePaths")
                .contains("Do not retry the whole-Project scope");
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .readFile(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void experimentSummaryUsesMetricFiltersAndDoesNotTreatMalformedJsonAsComplete() throws Exception {
        ProjectService service = serviceWithFixture(); authorizeAll();
        ProjectExperimentSummaryToolExecutor executor = new ProjectExperimentSummaryToolExecutor(service, json);
        var filtered = executor.execute(new ToolCall("json", "project_experiment_summary",
                metricPaths("metrics.json", "loss")));
        assertThat(filtered.success()).isTrue();
        assertThat(filtered.output().path("status").asText()).isEqualTo("COMPLETE");
        assertThat(filtered.output().path("items").get(0).path("metricName").asText()).isEqualTo("loss");

        Files.writeString(tempDir.resolve("server/study/metrics.json"), "{bad");
        var malformed = executor.execute(new ToolCall("bad-json", "project_experiment_summary", paths("metrics.json")));
        assertThat(malformed.success()).isTrue();
        assertThat(malformed.output().path("status").asText()).isEqualTo("PARSE_FAILED");
    }

    private void assertComplete(com.yanban.core.tool.ToolExecutor executor, String name, com.fasterxml.jackson.databind.node.ObjectNode args) {
        var result = executor.execute(new ToolCall("call-" + name, name, args));
        assertThat(result.success()).isTrue();
        assertThat(result.output().path("status").asText()).isIn("COMPLETE", "PARTIAL");
        assertThat(result.output().path("items").size()).isPositive();
        assertThat(result.output().path("evidenceRefs").size()).isPositive();
        assertThat(result.output().path("items").get(0).path("content").path("evidence").path("relativePath").asText()).doesNotContain(":\\");
        assertThat(result.output().path("items").get(0).path("content").path("trustLabel").asText())
                .isEqualTo("UNTRUSTED_PROJECT_CONTENT");
        assertThat(result.output().path("evidenceRefs").get(0).path("trustLabel").asText())
                .isEqualTo("SERVER_ATTESTED_METADATA");
        assertThat(result.version()).matches("[a-f0-9]{64}");
    }

    private void authorizeAll() {
        ToolExecutionContext.setCurrentUserId(7L); ToolExecutionContext.setCurrentProjectId(42L);
        ToolExecutionContext.setResolvedAllowedTools(Set.of("project_latex_outline", "project_bibtex_audit", "project_code_symbols",
                "project_experiment_summary", "project_cross_material_search"));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode paths(String... paths) {
        com.fasterxml.jackson.databind.node.ObjectNode result = json.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode values = result.putArray("relativePaths");
        for (String path : paths) values.add(path);
        return result;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode queryPaths(String query, String... paths) {
        com.fasterxml.jackson.databind.node.ObjectNode result = paths(paths); result.put("query", query); return result;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode metricPaths(String path, String metric) {
        com.fasterxml.jackson.databind.node.ObjectNode result = paths(path); result.putArray("metricNames").add(metric); return result;
    }

    private ProjectService serviceWithFixture() throws Exception {
        Path server = Files.createDirectories(tempDir.resolve("server")); Path root = Files.createDirectories(server.resolve("study"));
        Files.writeString(root.resolve("paper.tex"), "\\section{Method}\\label{sec:method}\nlearning_rate appears here\n\\cite{missing}\n");
        Files.writeString(root.resolve("refs.bib"), "@article{dup,\n title = {A},\n author = {B},\n year = {2024}\n}\n@article{dup,\n title = {B}\n}\n");
        Files.writeString(root.resolve("Main.java"), "package test; class Main { public static void main(String[] args) {} }\n");
        Files.writeString(root.resolve("run.py"), "import numpy\ndef train():\n    pass\n");
        Files.writeString(root.resolve("fit.m"), "function fit(x)\nend\n");
        Files.writeString(root.resolve("results.csv"), "epoch,accuracy\n1,0.8\n2,0.9\n");
        Files.writeString(root.resolve("config.yaml"), "learning_rate: 0.01\n");
        Files.writeString(root.resolve("report.txt"), "run completed\n"); Files.writeString(root.resolve("metrics.json"), "{\"loss\": 0.1}\n");
        Files.write(root.resolve("artifact.png"), new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0, 1});
        properties.setLocalServerRoot(server.toString());
        Project project = new Project(7L, "Study", "study", root.toRealPath().toString(), "[\"**\"]", "[]");
        when(repository.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        return new ProjectService(repository, List.of(new LocalServerProjectRootProvider(properties)), properties, json);
    }
}
