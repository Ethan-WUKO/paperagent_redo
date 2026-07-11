package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.project.ProjectService;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolErrorCode;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import java.util.List;

/** Shared fail-closed bridge from governed tool execution to ProjectService. */
abstract class AbstractProjectReadToolExecutor implements ToolExecutor {

    protected final ProjectService projects;
    protected final ObjectMapper objectMapper;

    AbstractProjectReadToolExecutor(ProjectService projects, ObjectMapper objectMapper) {
        this.projects = projects;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(definition().name(), "v1", "project-read",
                List.of(ToolDescriptor.CapabilityProfile.PROJECT), List.of("project:read"),
                List.of(ToolDescriptor.ResourceScope.PROJECT), ToolDescriptor.SideEffectType.NONE,
                ToolDescriptor.ConfirmationPolicy.NEVER, ToolDescriptor.AsyncMode.SYNC,
                ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, true);
    }

    protected Long requireTrustedProject(ToolCall call) {
        Long userId = ToolExecutionContext.getCurrentUserId();
        Long trustedProjectId = ToolExecutionContext.getCurrentProjectId();
        Long requestedProjectId = call.arguments() == null || !call.arguments().path("projectId").canConvertToLong()
                ? null : call.arguments().path("projectId").longValue();
        if (userId == null || trustedProjectId == null || requestedProjectId == null
                || !trustedProjectId.equals(requestedProjectId)) {
            return null;
        }
        // Re-assert ownership/read-only state at the executor boundary on every call.
        projects.manifest(userId, trustedProjectId);
        return trustedProjectId;
    }

    protected ToolResult rejected(ToolCall call) {
        return ToolResult.failure(call.id(), definition().name(), ToolErrorCode.PERMISSION_DENIED,
                "Project tool requires the authenticated project context and matching projectId.");
    }

    protected ObjectNode evidence(ObjectNode output, Long projectId, String relativePath, String version) {
        output.put("projectId", projectId);
        output.put("relativePath", relativePath);
        output.put("hash", version);
        output.put("version", version);
        output.put("trust", "UNTRUSTED");
        return output;
    }

    protected ToolResult success(ToolCall call, ObjectNode output, Long projectId, String relativePath, String version) {
        String ref = "project:" + projectId + ":" + relativePath + ":" + version + ":" + call.id();
        output.putArray("evidenceRefs").add(ref);
        return new ToolResult(call.id(), definition().name(), true, output, null, null, false,
                List.of(ref), List.of(), List.of(), version);
    }
}
