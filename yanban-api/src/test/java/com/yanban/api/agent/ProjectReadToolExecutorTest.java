package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectSearchHit;
import com.yanban.api.project.ProjectService;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolExecutionContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ProjectReadToolExecutorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectService projects = Mockito.mock(ProjectService.class);
    private static final String PROJECT_VERSION = "b".repeat(64);
    private static final String FILE_HASH = "a".repeat(64);

    @AfterEach
    void clearContext() { ToolExecutionContext.clear(); }

    @Test
    void readsOnlyTheServerAttestedProjectAndReturnsProvenance() {
        when(projects.manifest(7L, 11L)).thenReturn(new ProjectManifestResponse(11L, PROJECT_VERSION, List.of()));
        when(projects.readFile(7L, 11L, "src/Main.java"))
                .thenReturn(new ProjectFileResponse("src/Main.java", "class Main {}", 13, Instant.EPOCH, FILE_HASH));
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setCurrentProjectId(11L);
        ProjectReadFileToolExecutor executor = new ProjectReadFileToolExecutor(projects, json);

        var result = executor.execute(new ToolCall("c1", "project_read_file", json.createObjectNode()
                .put("relativePath", "src/Main.java")));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("projectId").asLong()).isEqualTo(11L);
        assertThat(result.output().path("relativePath").asText()).isEqualTo("src/Main.java");
        assertThat(result.output().path("hash").asText()).isEqualTo(FILE_HASH);
        assertThat(result.output().path("projectVersion").asText()).isEqualTo(PROJECT_VERSION);
        assertThat(result.output().path("parserVersion").asText())
                .isEqualTo(ProjectReadFileToolExecutor.PARSER_VERSION);
        assertThat(result.output().path("trust").asText()).isEqualTo("UNTRUSTED");
        Mockito.verify(projects, Mockito.times(1)).manifest(7L, 11L);
    }

    @Test
    void readsOnlyTheRequestedInclusiveLineRange() {
        when(projects.manifest(7L, 11L)).thenReturn(new ProjectManifestResponse(11L, PROJECT_VERSION, List.of()));
        when(projects.readFile(7L, 11L, "paper.tex"))
                .thenReturn(new ProjectFileResponse("paper.tex", "line1\nline2\nline3\nline4", 23,
                        Instant.EPOCH, FILE_HASH));
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setCurrentProjectId(11L);

        var result = new ProjectReadFileToolExecutor(projects, json).execute(new ToolCall(
                "range",
                "project_read_file",
                json.createObjectNode().put("relativePath", "paper.tex").put("startLine", 2).put("endLine", 3)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("startLine").asInt()).isEqualTo(2);
        assertThat(result.output().path("endLine").asInt()).isEqualTo(3);
        assertThat(result.output().path("content").asText()).isEqualTo("line2\nline3");
    }

    @Test
    void ignoresAProjectIdInventedByTheModelAndUsesServerContext() {
        when(projects.manifest(7L, 11L)).thenReturn(new ProjectManifestResponse(11L, PROJECT_VERSION, List.of()));
        when(projects.readFile(7L, 11L, "safe.txt"))
                .thenReturn(new ProjectFileResponse("safe.txt", "safe", 4, Instant.EPOCH, FILE_HASH));
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setCurrentProjectId(11L);
        ProjectReadFileToolExecutor executor = new ProjectReadFileToolExecutor(projects, json);

        var result = executor.execute(new ToolCall("c2", "project_read_file", json.createObjectNode()
                .put("projectId", 12).put("relativePath", "safe.txt")));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("projectId").asLong()).isEqualTo(11L);
        Mockito.verify(projects).readFile(7L, 11L, "safe.txt");
    }

    @Test
    void modelSchemasDoNotExposeProjectIdentity() {
        assertThat(new ProjectManifestToolExecutor(projects, json).definition().parameters().toString())
                .doesNotContain("projectId");
        assertThat(new ProjectReadFileToolExecutor(projects, json).definition().parameters().toString())
                .doesNotContain("projectId");
        assertThat(new ProjectSearchToolExecutor(projects, json).definition().parameters().toString())
                .doesNotContain("projectId");
    }

    @Test
    void searchesLiteralLatexCommandsWithBraces() {
        when(projects.manifest(7L, 11L)).thenReturn(new ProjectManifestResponse(11L, PROJECT_VERSION, List.of()));
        when(projects.search(7L, 11L, "\\section{INTRODUCTION}", 5)).thenReturn(List.of(
                new ProjectSearchHit("paper.tex", 69, "\\section{INTRODUCTION}", FILE_HASH),
                new ProjectSearchHit("appendix.tex", 107, "\\section{INTRODUCTION}", "c".repeat(64))));
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setCurrentProjectId(11L);

        var result = new ProjectSearchToolExecutor(projects, json).execute(new ToolCall("search-latex", "project_search",
                json.createObjectNode().put("query", "\\section{INTRODUCTION}").put("maxResults", 5)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("hits").get(0).path("lineNumber").asInt()).isEqualTo(69);
        assertThat(result.output().path("hits").get(1).path("lineNumber").asInt()).isEqualTo(107);
        assertThat(result.output().path("hits")).allSatisfy(hit ->
                assertThat(hit.path("projectVersion").asText()).isEqualTo(PROJECT_VERSION));
        assertThat(result.output().path("hits")).allSatisfy(hit ->
                assertThat(hit.path("parserVersion").asText())
                        .isEqualTo(ProjectSearchToolExecutor.PARSER_VERSION));
        Mockito.verify(projects, Mockito.times(1)).manifest(7L, 11L);
    }

    @Test
    void zeroHitSearchStillBelongsToTheSingleTrustedManifestSnapshot() {
        when(projects.manifest(7L, 11L)).thenReturn(new ProjectManifestResponse(11L, PROJECT_VERSION, List.of()));
        when(projects.search(7L, 11L, "missing-token", 5)).thenReturn(List.of());
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setCurrentProjectId(11L);

        var result = new ProjectSearchToolExecutor(projects, json).execute(new ToolCall("search-empty", "project_search",
                json.createObjectNode().put("query", "missing-token").put("maxResults", 5)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("hits")).isEmpty();
        assertThat(result.output().path("projectVersion").asText()).isEqualTo(PROJECT_VERSION);
        Mockito.verify(projects, Mockito.times(1)).manifest(7L, 11L);
    }

    @Test
    void reportsInvalidSearchSyntaxAsValidationFailure() {
        when(projects.manifest(7L, 11L)).thenReturn(new ProjectManifestResponse(11L, PROJECT_VERSION, List.of()));
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setCurrentProjectId(11L);

        var result = new ProjectSearchToolExecutor(projects, json).execute(new ToolCall("search-glob", "project_search",
                json.createObjectNode().put("query", "*.tex")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode().name()).isEqualTo("VALIDATION_ERROR");
        assertThat(result.errorMessage()).contains("literal query");
    }
}
