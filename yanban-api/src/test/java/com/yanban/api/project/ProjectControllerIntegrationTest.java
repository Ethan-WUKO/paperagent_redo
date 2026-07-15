package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockMultipartFile;
import com.yanban.api.security.JwtUser;
import com.yanban.api.agent.ProjectAgentRuntimeService;
import com.yanban.api.agent.SendMessageResponse;
import com.yanban.api.agent.AgentPlanResponse;
import com.yanban.api.agent.ProjectEvidenceResponse;
import com.yanban.api.agent.AgentSessionResponse;
import com.yanban.core.agent.AgentSessionScope;

class ProjectControllerIntegrationTest {

    private MockMvc mockMvc;
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectStorageProperties properties = new ProjectStorageProperties();

    @TempDir
    Path tempDir;

    private Path projectRoot;

    @BeforeEach
    void setUp() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Files.writeString(projectRoot.resolve("notes.txt"), "safe");
        Files.writeString(projectRoot.resolve("large.txt"), "x".repeat(33));
        properties.setLocalServerRoot(serverRoot.toString());
        properties.setAllowLocalAbsoluteProjectFolders(true);
        Project project = new Project(7L, "Study", "study", projectRoot.toRealPath().toString(), "[\"**\"]", "[]");
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        when(projects.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new JwtUser(7L, "project-test-user"), "n/a"));
        ProjectService projectService = new ProjectService(projects,
                List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(projectService))
                .setControllerAdvice(new ProjectExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void normalReadReturnsRelativePathOnly() throws Exception {
        mockMvc.perform(get("/api/v1/projects/42/files/read").param("path", "notes.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("notes.txt"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("C:\\"))));
    }

    @Test
    void invalidAbsoluteOrTraversalPathReturnsStableBadRequestWithoutRoot() throws Exception {
        mockMvc.perform(get("/api/v1/projects/42/files/read").param("path", "C:\\private\\secret.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROJECT_PATH"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        mockMvc.perform(get("/api/v1/projects/42/files/read").param("path", "../secret.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROJECT_PATH"));
    }

    @Test
    void unauthorizedAndOversizedFileRemainNotFound() throws Exception {
        properties.setMaxFileBytes(32);

        mockMvc.perform(get("/api/v1/projects/42/files/read").param("path", "large.txt"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/99/files/read").param("path", "notes.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void traversalLimitReturnsStructuredPayloadTooLarge() throws Exception {
        properties.setMaxFiles(1);

        mockMvc.perform(get("/api/v1/projects/42/manifest"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PROJECT_LIMIT_EXCEEDED"));
    }

    @Test
    void authenticatedProjectRoutesBindProjectIdFromPathAndDelegateToFacade() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectAgentRuntimeService facade = mock(ProjectAgentRuntimeService.class);
        when(facade.send(7L, 42L, 8L, new com.yanban.api.agent.SendMessageRequest("read notes", null, null, null, null)))
                .thenReturn(null);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, facade))
                .setControllerAdvice(new ProjectExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        mockMvc.perform(post("/api/v1/projects/42/agent/sessions/8/messages")
                        .contentType("application/json").content("{\"content\":\"read notes\",\"projectId\":99}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(facade).send(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(post("/api/v1/projects/42/agent/sessions/8/plans")
                        .contentType("application/json").content("{\"content\":\"compare files\",\"ragDisabled\":true,\"autoExecute\":true}"))
                .andExpect(status().isCreated());
        org.mockito.ArgumentCaptor<com.yanban.api.agent.CreateAgentPlanRequest> planRequest =
                org.mockito.ArgumentCaptor.forClass(com.yanban.api.agent.CreateAgentPlanRequest.class);
        org.mockito.Mockito.verify(facade).createPlan(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(8L), planRequest.capture());
        org.assertj.core.api.Assertions.assertThat(planRequest.getValue()).isEqualTo(
                new com.yanban.api.agent.CreateAgentPlanRequest("compare files", true, null, true));
    }

    @Test
    void projectSessionHistoryRoutesUseAuthenticatedUserAndPathProjectId() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectAgentRuntimeService facade = mock(ProjectAgentRuntimeService.class);
        AgentSessionResponse session = new AgentSessionResponse(8L, 7L, AgentSessionScope.PROJECT, 42L,
                "Study conversation", "deepseek", "deepseek-v4-flash", 20, true, null, null);
        when(facade.listSessions(7L, 42L)).thenReturn(List.of(session));
        when(facade.createSession(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any())).thenReturn(session);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, facade))
                .setControllerAdvice(new ProjectExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        mockMvc.perform(get("/api/v1/projects/42/agent/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scope").value("PROJECT"))
                .andExpect(jsonPath("$[0].projectId").value(42));

        mockMvc.perform(post("/api/v1/projects/42/agent/sessions")
                        .contentType("application/json")
                        .content("{\"title\":\"Study conversation\",\"ragDisabled\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("PROJECT"));

        org.mockito.Mockito.verify(facade).listSessions(7L, 42L);
        org.mockito.Mockito.verify(facade).createSession(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void projectPlanFailureReturnsDiagnosticJsonInsteadOfBareBadGateway() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectAgentRuntimeService facade = mock(ProjectAgentRuntimeService.class);
        when(facade.createPlan(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Project Plan creation failed [traceId=plan-create-test]: Planner failed [MODEL_CALL_FAILED]: upstream timeout"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, facade))
                .setControllerAdvice(new ProjectExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        mockMvc.perform(post("/api/v1/projects/42/agent/sessions/8/plans")
                        .contentType("application/json")
                        .content("{\"content\":\"compare files\",\"ragDisabled\":true,\"autoExecute\":true}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROJECT_PLAN_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("plan-create-test")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("MODEL_CALL_FAILED")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("upstream timeout")));
        org.mockito.Mockito.verify(facade).createPlan(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(8L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @org.junit.jupiter.api.Disabled("Legacy absolute-path creation endpoint was removed")
    void createUsesAuthenticatedOwnerAndNeverAcceptsCallerSelectedOwnership() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectProvisioningService provisioning = mock(ProjectProvisioningService.class);
        when(provisioning.provision(org.mockito.ArgumentMatchers.any())).thenReturn(
                new Project(7L, "Created", "study", projectRoot.toRealPath().toString(), "[\"**\"]", "[]"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service))
                .setControllerAdvice(new ProjectExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        mockMvc.perform(post("/api/v1/projects").contentType("application/json").content("""
                {"userId":999,"accessMode":"READ_WRITE","name":"Created","projectFolder":"C:\\\\科研项目\\\\FDA-MIMO","includeRules":["**"],"ignoreRules":[]}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Created"))
                .andExpect(jsonPath("$.accessMode").value("READ_ONLY"));
        org.mockito.ArgumentCaptor<ProjectProvisioningRequest> request = org.mockito.ArgumentCaptor.forClass(ProjectProvisioningRequest.class);
        org.mockito.Mockito.verify(provisioning).provision(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().userId()).isEqualTo(7L);
        org.assertj.core.api.Assertions.assertThat(request.getValue().projectFolder()).isEqualTo("C:\\科研项目\\FDA-MIMO");
    }

    @Test
    void legacyAbsoluteFolderJsonCreationIsNoLongerExposed() throws Exception {
        mockMvc.perform(post("/api/v1/projects").contentType("application/json").content("""
                {"name":"Created","projectFolder":"C:\\\\Research\\\\Study","includeRules":["**"],"ignoreRules":[]}
                """))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void browserFolderUploadBindsAuthenticatedOwnerAndMultipartRelativeFiles() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectUploadService uploadService = mock(ProjectUploadService.class);
        when(uploadService.upload(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("Uploaded"),
                org.mockito.ArgumentMatchers.eq(List.of("**")), org.mockito.ArgumentMatchers.eq(List.of("target/**")),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(
                Project.managedUpload(7L, "Uploaded", projectRoot.toRealPath().toString(), "[\"**\"]", "[\"target/**\"]"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, null, uploadService))
                .setControllerAdvice(new ProjectExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        MockMultipartFile file = new MockMultipartFile("files", "study/notes.txt", "text/plain", "safe".getBytes());
        mockMvc.perform(multipart("/api/v1/projects")
                        .file(file)
                        .param("name", "Uploaded")
                        .param("includeRules", "**")
                        .param("ignoreRules", "target/**"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Uploaded"))
                .andExpect(jsonPath("$.accessMode").value("READ_ONLY"));

        org.mockito.ArgumentCaptor<List<org.springframework.web.multipart.MultipartFile>> files =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(uploadService).upload(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("Uploaded"), org.mockito.ArgumentMatchers.eq(List.of("**")),
                org.mockito.ArgumentMatchers.eq(List.of("target/**")), files.capture());
        assertThat(files.getValue()).hasSize(1);
        assertThat(files.getValue().get(0).getOriginalFilename()).isEqualTo("study/notes.txt");
    }

    @Test
    void deleteRemovesOnlyTheAuthenticatedUsersProjectBinding() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/42"))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(projects).findByIdAndUserId(42L, 7L);
        org.mockito.Mockito.verify(projects).delete(org.mockito.ArgumentMatchers.any(Project.class));
    }

    @Test
    void deleteReturnsTheSameNotFoundForMissingOrNonOwnedProject() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/99"))
                .andExpect(status().isNotFound());

        org.mockito.Mockito.verify(projects).findByIdAndUserId(99L, 7L);
        org.mockito.Mockito.verify(projects, org.mockito.Mockito.never())
                .delete(org.mockito.ArgumentMatchers.any(Project.class));
    }

    @Test
    void evidenceProjectionDelegatesOnlyTheAuthenticatedProjectAndPlanRoute() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectAgentRuntimeService facade = mock(ProjectAgentRuntimeService.class);
        when(facade.listPlanEvidence(7L, 42L, 13L)).thenReturn(List.of(
                new ProjectEvidenceResponse("trusted-plan:42:notes.txt:h:1", "notes.txt", "h", "h", "tool:1", true, true)));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, facade))
                .setControllerAdvice(new ProjectExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        mockMvc.perform(get("/api/v1/projects/42/agent/plans/13/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relativePath").value("notes.txt"))
                .andExpect(jsonPath("$[0].current").value(true));
        org.mockito.Mockito.verify(facade).listPlanEvidence(7L, 42L, 13L);
    }

    @Test
    void projectRoutesRejectNonOwnerBeforeFacadeCanBindCapability() throws Exception {
        ProjectService service = new ProjectService(projects, List.of(new LocalServerProjectRootProvider(properties)), properties, new ObjectMapper());
        ProjectAgentRuntimeService facade = new ProjectAgentRuntimeService(service,
                mock(com.yanban.api.agent.AgentService.class), mock(com.yanban.api.agent.PlanAgentService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, facade))
                .setControllerAdvice(new ProjectExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        mockMvc.perform(post("/api/v1/projects/99/agent/sessions/8/messages")
                        .contentType("application/json").content("{\"content\":\"read notes\"}"))
                .andExpect(status().isNotFound());
    }
}
