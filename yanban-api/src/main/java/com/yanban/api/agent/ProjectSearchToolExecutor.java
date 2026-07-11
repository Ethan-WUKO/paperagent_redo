package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.project.ProjectSearchHit;
import com.yanban.api.project.ProjectService;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolResult;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProjectSearchToolExecutor extends AbstractProjectReadToolExecutor {
    private final ToolDefinition definition;
    public ProjectSearchToolExecutor(ProjectService projects, ObjectMapper objectMapper) {
        super(projects, objectMapper);
        ObjectNode schema = objectMapper.createObjectNode(); schema.put("type", "object"); ObjectNode p = schema.putObject("properties");
        p.putObject("projectId").put("type", "integer"); p.putObject("query").put("type", "string").put("description", "Literal bounded text query; no glob or filesystem expression.");
        p.putObject("maxResults").put("type", "integer").put("description", "Bounded result count.");
        schema.putArray("required").add("projectId").add("query"); schema.put("additionalProperties", false);
        definition = new ToolDefinition("project_search", "Search safe text files in the authorized Project with a literal query.", schema);
    }
    @Override public ToolDefinition definition() { return definition; }
    @Override public ToolResult execute(ToolCall call) {
        Long projectId = requireTrustedProject(call); String query = call.arguments() == null ? null : call.arguments().path("query").asText(null);
        if (projectId == null || !StringUtils.hasText(query) || !isLiteralQuery(query)) return rejected(call);
        Integer max = call.arguments().has("maxResults") ? call.arguments().path("maxResults").asInt() : null;
        List<ProjectSearchHit> hits = projects.search(com.yanban.core.tool.ToolExecutionContext.getCurrentUserId(), projectId, query, max);
        ObjectNode output = objectMapper.createObjectNode(); output.put("projectId", projectId); output.put("query", query); output.put("trust", "UNTRUSTED");
        var items = output.putArray("hits"); for (ProjectSearchHit hit : hits) { ObjectNode item = items.addObject(); evidence(item, projectId, hit.path(), hit.sha256()); item.put("lineNumber", hit.lineNumber()); item.put("line", hit.line()); }
        String version = hits.isEmpty() ? "no-hit" : hits.get(0).sha256();
        if (!hits.isEmpty()) evidence(output, projectId, hits.get(0).path(), version);
        return success(call, output, projectId, "search:" + query, version);
    }

    private boolean isLiteralQuery(String query) {
        return query.indexOf('\0') < 0 && query.chars().noneMatch(ch -> ch == '*' || ch == '?' || ch == '[' || ch == ']'
                || ch == '{' || ch == '}');
    }
}
