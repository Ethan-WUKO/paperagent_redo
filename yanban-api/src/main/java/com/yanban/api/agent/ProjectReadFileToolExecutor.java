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
    static final String PARSER_VERSION = "project-read-file@1";
    private final ToolDefinition definition;
    public ProjectReadFileToolExecutor(ProjectService projects, ObjectMapper objectMapper) {
        super(projects, objectMapper);
        ObjectNode schema = objectMapper.createObjectNode(); schema.put("type", "object"); ObjectNode p = schema.putObject("properties");
        p.putObject("relativePath").put("type", "string").put("description", "A Project-relative readable text path; absolute paths and traversal are rejected.");
        p.putObject("startLine").put("type", "integer").put("minimum", 1).put("description", "Optional 1-based inclusive first line.");
        p.putObject("endLine").put("type", "integer").put("minimum", 1).put("description", "Optional 1-based inclusive last line; must be >= startLine.");
        schema.putArray("required").add("relativePath"); schema.put("additionalProperties", false);
        definition = new ToolDefinition("project_read_file", "Read one safe text file from the authorized Project using only a relative path.", schema);
    }
    @Override public ToolDefinition definition() { return definition; }
    @Override public ToolResult execute(ToolCall call) {
        TrustedProject project = requireTrustedProject(call);
        String relativePath = call.arguments() == null ? null : call.arguments().path("relativePath").asText(null);
        if (project == null || !StringUtils.hasText(relativePath)) return rejected(call);
        ProjectFileResponse file = projects.readFile(project.userId(), project.projectId(), relativePath);
        int startLine = call.arguments().has("startLine") ? call.arguments().path("startLine").asInt() : 1;
        String[] lines = file.content().split("\\R", -1);
        int endLine = call.arguments().has("endLine") ? call.arguments().path("endLine").asInt() : lines.length;
        if (startLine < 1 || endLine < startLine || startLine > lines.length) {
            return ToolResult.failure(call.id(), definition().name(), com.yanban.core.tool.ToolErrorCode.VALIDATION_ERROR,
                    "Line range must be 1-based, startLine <= endLine, and startLine must exist in the file.");
        }
        endLine = Math.min(endLine, lines.length);
        String content = String.join("\n", java.util.Arrays.copyOfRange(lines, startLine - 1, endLine));
        ObjectNode output = objectMapper.createObjectNode(); evidence(output, project, file.path(), file.sha256());
        output.put("sizeBytes", file.sizeBytes()); output.put("modifiedAt", file.modifiedAt().toString());
        output.put("startLine", startLine); output.put("endLine", endLine);
        output.put("parserVersion", PARSER_VERSION); output.put("content", content);
        return success(call, output, project.projectId(), file.path(), file.sha256());
    }
}
