package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProjectReadFileToolExecutor extends AbstractProjectReadToolExecutor {
    private final ToolDefinition definition;
    public ProjectReadFileToolExecutor(ProjectService projects, ObjectMapper objectMapper) {
        super(projects, objectMapper);
        ObjectNode schema = objectMapper.createObjectNode(); schema.put("type", "object"); ObjectNode p = schema.putObject("properties");
        p.putObject("projectId").put("type", "integer");
        p.putObject("relativePath").put("type", "string").put("description", "A Project-relative readable text path; absolute paths and traversal are rejected.");
        schema.putArray("required").add("projectId").add("relativePath"); schema.put("additionalProperties", false);
        definition = new ToolDefinition("project_read_file", "Read one safe text file from the authorized Project using only a relative path.", schema);
    }
    @Override public ToolDefinition definition() { return definition; }
    @Override public ToolResult execute(ToolCall call) {
        Long projectId = requireTrustedProject(call);
        String relativePath = call.arguments() == null ? null : call.arguments().path("relativePath").asText(null);
        if (projectId == null || !StringUtils.hasText(relativePath)) return rejected(call);
        ProjectFileResponse file = projects.readFile(com.yanban.core.tool.ToolExecutionContext.getCurrentUserId(), projectId, relativePath);
        ObjectNode output = objectMapper.createObjectNode(); evidence(output, projectId, file.path(), file.sha256());
        output.put("sizeBytes", file.sizeBytes()); output.put("modifiedAt", file.modifiedAt().toString()); output.put("content", file.content());
        return success(call, output, projectId, file.path(), file.sha256());
    }
}
