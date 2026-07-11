package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class ProjectManifestToolExecutor extends AbstractProjectReadToolExecutor {
    private final ToolDefinition definition;

    public ProjectManifestToolExecutor(ProjectService projects, ObjectMapper objectMapper) {
        super(projects, objectMapper);
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("projectId").put("type", "integer")
                .put("description", "The currently authorized project id; it must match the runtime context.");
        schema.putArray("required").add("projectId");
        schema.put("additionalProperties", false);
        definition = new ToolDefinition("project_manifest", "List safe, readable files in the authorized read-only Project. Never use paths outside that Project.", schema);
    }
    @Override public ToolDefinition definition() { return definition; }
    @Override public ToolResult execute(ToolCall call) {
        Long projectId = requireTrustedProject(call);
        if (projectId == null) return rejected(call);
        ProjectManifestResponse manifest = projects.manifest(com.yanban.core.tool.ToolExecutionContext.getCurrentUserId(), projectId);
        ObjectNode output = objectMapper.createObjectNode();
        evidence(output, projectId, "manifest", manifest.version());
        output.put("fileCount", manifest.files().size());
        var files = output.putArray("files");
        for (ProjectFileEntry file : manifest.files()) {
            ObjectNode item = files.addObject();
            item.put("relativePath", file.path()); item.put("sizeBytes", file.sizeBytes()); item.put("hash", file.sha256());
            item.put("version", file.sha256());
        }
        return success(call, output, projectId, "manifest", manifest.version());
    }
}
