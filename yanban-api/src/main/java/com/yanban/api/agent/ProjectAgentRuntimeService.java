package com.yanban.api.agent;

import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Authenticated API adapter that is the only HTTP path permitted to bind a Project to runtime. */
@Service
public class ProjectAgentRuntimeService {
    private static final int CANONICAL_CHUNK_CODE_POINTS = 64;

    private final ProjectService projects;
    private final AgentService agentService;
    private final PlanAgentService planAgentService;

    public ProjectAgentRuntimeService(ProjectService projects, AgentService agentService, PlanAgentService planAgentService) {
        this.projects = projects;
        this.agentService = agentService;
        this.planAgentService = planAgentService;
    }

    public AgentPlanResponse createPlan(Long userId, Long projectId, Long sessionId, CreateAgentPlanRequest request) {
        return planAgentService.createProjectPlan(userId, projectId, sessionId, request);
    }

    public SendMessageResponse send(Long userId, Long projectId, Long sessionId, SendMessageRequest request) {
        var manifest = projects.manifest(userId, projectId);
        SendMessageResponse response = agentService.sendProjectMessage(userId, sessionId, request,
                new ProjectRuntimeContext(userId, projectId));
        return projectResponse(response, manifest);
    }

    /**
     * Streams process observations immediately, but releases assistant content only after the Project completion
     * gate has selected its canonical response. Runtime attempt tokens are deliberately suppressed: a bounded
     * repair may execute more than once and must never concatenate unverified attempts in the client bubble.
     */
    public SendMessageResponse sendStreaming(Long userId,
                                             Long projectId,
                                             Long sessionId,
                                             SendMessageRequest request,
                                             Consumer<String> canonicalTokenConsumer,
                                             Consumer<String> processConsumer) {
        var manifest = projects.manifest(userId, projectId);
        SendMessageResponse response = agentService.sendProjectMessageStreaming(
                userId,
                sessionId,
                request,
                new ProjectRuntimeContext(userId, projectId),
                ignoredAttemptToken -> { },
                processConsumer
        );
        SendMessageResponse projected = projectResponse(response, manifest);
        if (projected.success() && canonicalTokenConsumer != null
                && StringUtils.hasText(projected.assistantContent())) {
            emitCanonicalChunks(projected.assistantContent(), canonicalTokenConsumer);
        }
        return projected;
    }

    private void emitCanonicalChunks(String content, Consumer<String> consumer) {
        int start = 0;
        while (start < content.length()) {
            int remainingCodePoints = content.codePointCount(start, content.length());
            int chunkCodePoints = Math.min(CANONICAL_CHUNK_CODE_POINTS, remainingCodePoints);
            int end = content.offsetByCodePoints(start, chunkCodePoints);
            consumer.accept(content.substring(start, end));
            start = end;
        }
    }

    private SendMessageResponse projectResponse(SendMessageResponse response, ProjectManifestResponse manifest) {
        List<ProjectEvidenceResponse> source = response.projectEvidence() == null
                ? List.of() : response.projectEvidence();
        List<ProjectEvidenceResponse> current = source.stream()
                .filter(item -> item.trusted())
                .filter(item -> isSafeRelativePath(item.relativePath()))
                .map(item -> new ProjectEvidenceResponse(item.id(), item.relativePath(), item.hash(), item.version(),
                        item.chunk(), true, manifest.files().stream().anyMatch(file -> file.path().equals(item.relativePath())
                                && file.sha256().equals(item.hash()))))
                .toList();
        return new SendMessageResponse(response.success(), response.assistantContent(), response.steps(), response.errorMessage(),
                response.navigationUrl(), response.messages(), response.debug(), current);
    }

    private boolean isSafeRelativePath(String value) {
        if (!StringUtils.hasText(value) || value.startsWith("/") || value.startsWith("\\")
                || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    public List<ProjectEvidenceResponse> listPlanEvidence(Long userId, Long projectId, Long planId) {
        return planAgentService.listProjectEvidence(userId, projectId, planId);
    }

}
