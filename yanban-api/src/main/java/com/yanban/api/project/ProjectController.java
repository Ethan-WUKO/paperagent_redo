package com.yanban.api.project;

import java.util.List;
import com.yanban.api.agent.ProjectAgentRuntimeService;
import com.yanban.api.agent.SendMessageRequest;
import com.yanban.api.agent.SendMessageResponse;
import com.yanban.api.agent.AgentPlanResponse;
import com.yanban.api.agent.CreateAgentPlanRequest;
import com.yanban.api.agent.ProjectEvidenceResponse;
import com.yanban.api.agent.AgentSessionResponse;
import com.yanban.api.agent.CreateSessionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectAgentRuntimeService projectAgentRuntimeService;
    private final ProjectUploadService projectUploadService;

    /** Compatibility constructor for focused existing controller tests. */
    public ProjectController(ProjectService projectService) {
        this(projectService, null, (ProjectUploadService) null);
    }

    public ProjectController(ProjectService projectService, ProjectAgentRuntimeService projectAgentRuntimeService) {
        this(projectService, projectAgentRuntimeService, (ProjectUploadService) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectController(ProjectService projectService, ProjectAgentRuntimeService projectAgentRuntimeService,
                             ProjectUploadService projectUploadService) {
        this.projectService = projectService;
        this.projectAgentRuntimeService = projectAgentRuntimeService;
        this.projectUploadService = projectUploadService;
    }

    @GetMapping
    public List<ProjectSummaryResponse> list(@AuthenticationPrincipal(expression = "id") Long userId) {
        return projectService.list(userId);
    }

    /** Browser folder import: files are copied into server-owned storage and never mutate the source folder. */
    @org.springframework.web.bind.annotation.PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ProjectSummaryResponse upload(@AuthenticationPrincipal(expression = "id") Long userId,
                                         @RequestParam String name,
                                         @RequestParam List<String> includeRules,
                                         @RequestParam(required = false) List<String> ignoreRules,
                                         @org.springframework.web.bind.annotation.RequestPart("files") List<MultipartFile> files) {
        if (projectUploadService == null) {
            throw new IllegalStateException("Project upload is not configured");
        }
        return ProjectSummaryResponse.from(projectUploadService.upload(userId, name, includeRules,
                ignoreRules == null ? List.of() : ignoreRules, files));
    }

    /** Removes only the authenticated user's Project binding; it never deletes files from the bound folder. */
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal(expression = "id") Long userId,
                       @PathVariable Long projectId) {
        projectService.delete(userId, projectId);
    }

    @GetMapping("/{projectId}/manifest")
    public ProjectManifestResponse manifest(@AuthenticationPrincipal(expression = "id") Long userId,
                                            @PathVariable Long projectId) {
        return projectService.manifest(userId, projectId);
    }

    @GetMapping("/{projectId}/agent/sessions")
    public List<AgentSessionResponse> listProjectSessions(@AuthenticationPrincipal(expression = "id") Long userId,
                                                          @PathVariable Long projectId) {
        if (projectAgentRuntimeService == null) throw new IllegalStateException("Project runtime is not configured");
        return projectAgentRuntimeService.listSessions(userId, projectId);
    }

    @org.springframework.web.bind.annotation.PostMapping("/{projectId}/agent/sessions")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public AgentSessionResponse createProjectSession(@AuthenticationPrincipal(expression = "id") Long userId,
                                                     @PathVariable Long projectId,
                                                     @Valid @org.springframework.web.bind.annotation.RequestBody CreateSessionRequest request) {
        if (projectAgentRuntimeService == null) throw new IllegalStateException("Project runtime is not configured");
        return projectAgentRuntimeService.createSession(userId, projectId, request);
    }

    @GetMapping("/{projectId}/files/read")
    public ProjectFileResponse read(@AuthenticationPrincipal(expression = "id") Long userId,
                                    @PathVariable Long projectId,
                                    @RequestParam String path) {
        return projectService.readFile(userId, projectId, path);
    }

    @GetMapping("/{projectId}/search")
    public List<ProjectSearchHit> search(@AuthenticationPrincipal(expression = "id") Long userId,
                                          @PathVariable Long projectId,
                                          @RequestParam String query,
                                          @RequestParam(required = false) Integer maxResults) {
        return projectService.search(userId, projectId, query, maxResults);
    }

    /** Project binding comes from this authenticated route, never from chat JSON or model content. */
    @org.springframework.web.bind.annotation.PostMapping("/{projectId}/agent/sessions/{sessionId}/messages")
    public SendMessageResponse sendProjectMessage(@AuthenticationPrincipal(expression = "id") Long userId,
                                                  @PathVariable Long projectId,
                                                  @PathVariable Long sessionId,
                                                  @Valid @org.springframework.web.bind.annotation.RequestBody SendMessageRequest request) {
        if (projectAgentRuntimeService == null) {
            throw new IllegalStateException("Project runtime is not configured");
        }
        return projectAgentRuntimeService.send(userId, projectId, sessionId, request);
    }

    @org.springframework.web.bind.annotation.PostMapping("/{projectId}/agent/sessions/{sessionId}/plans")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public AgentPlanResponse createProjectPlan(@AuthenticationPrincipal(expression = "id") Long userId,
                                               @PathVariable Long projectId, @PathVariable Long sessionId,
                                               @Valid @org.springframework.web.bind.annotation.RequestBody CreateAgentPlanRequest request) {
        if (projectAgentRuntimeService == null) throw new IllegalStateException("Project runtime is not configured");
        return projectAgentRuntimeService.createPlan(userId, projectId, sessionId, request);
    }

    @GetMapping("/{projectId}/agent/plans/{planId}/evidence")
    public List<ProjectEvidenceResponse> listProjectPlanEvidence(@AuthenticationPrincipal(expression = "id") Long userId,
                                                                  @PathVariable Long projectId,
                                                                  @PathVariable Long planId) {
        if (projectAgentRuntimeService == null) throw new IllegalStateException("Project runtime is not configured");
        return projectAgentRuntimeService.listPlanEvidence(userId, projectId, planId);
    }
}
