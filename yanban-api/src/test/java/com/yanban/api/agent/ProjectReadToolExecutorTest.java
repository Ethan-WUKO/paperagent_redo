package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectManifestResponse;
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

    @AfterEach
    void clearContext() { ToolExecutionContext.clear(); }

    @Test
    void readsOnlyTheServerAttestedProjectAndReturnsProvenance() {
        when(projects.manifest(7L, 11L)).thenReturn(new ProjectManifestResponse(11L, "manifest-v1", List.of()));
        when(projects.readFile(7L, 11L, "src/Main.java"))
                .thenReturn(new ProjectFileResponse("src/Main.java", "class Main {}", 13, Instant.EPOCH, "sha-1"));
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setCurrentProjectId(11L);
        ProjectReadFileToolExecutor executor = new ProjectReadFileToolExecutor(projects, json);

        var result = executor.execute(new ToolCall("c1", "project_read_file", json.createObjectNode()
                .put("projectId", 11).put("relativePath", "src/Main.java")));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("projectId").asLong()).isEqualTo(11L);
        assertThat(result.output().path("relativePath").asText()).isEqualTo("src/Main.java");
        assertThat(result.output().path("hash").asText()).isEqualTo("sha-1");
        assertThat(result.output().path("trust").asText()).isEqualTo("UNTRUSTED");
    }

    @Test
    void rejectsAProjectIdInventedByTheModel() {
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setCurrentProjectId(11L);
        ProjectReadFileToolExecutor executor = new ProjectReadFileToolExecutor(projects, json);

        var result = executor.execute(new ToolCall("c2", "project_read_file", json.createObjectNode()
                .put("projectId", 12).put("relativePath", "../secret.txt")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode().name()).isEqualTo("PERMISSION_DENIED");
        Mockito.verifyNoInteractions(projects);
    }
}
