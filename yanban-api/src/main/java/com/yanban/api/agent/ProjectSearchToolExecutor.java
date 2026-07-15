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
    static final String PARSER_VERSION = "project-search@1";
    private final ToolDefinition definition;
    public ProjectSearchToolExecutor(ProjectService projects, ObjectMapper objectMapper) {
        super(projects, objectMapper);
        ObjectNode schema = objectMapper.createObjectNode(); schema.put("type", "object"); ObjectNode p = schema.putObject("properties");
        p.putObject("query").put("type", "string").put("description", "Literal bounded text query only. Regex operators and glob expressions are not supported; search one exact substring such as P_total.");
        p.putObject("maxResults").put("type", "integer").put("description", "Bounded result count.");
        schema.putArray("required").add("query"); schema.put("additionalProperties", false);
        definition = new ToolDefinition("project_search", "Search safe text files in the authorized Project using one literal substring. This is not regex or glob search.", schema);
    }
    @Override public ToolDefinition definition() { return definition; }
    @Override public ToolResult execute(ToolCall call) {
        TrustedProject project = requireTrustedProject(call); String query = call.arguments() == null ? null : call.arguments().path("query").asText(null);
        if (project == null) return rejected(call);
        if (!StringUtils.hasText(query) || !isLiteralQuery(query)) {
            return ToolResult.failure(call.id(), definition().name(), com.yanban.core.tool.ToolErrorCode.VALIDATION_ERROR,
                    "Project search accepts one literal query substring only; regex/glob expressions are unsupported. "
                            + "Replace the expression with a concrete token such as P_total or trace(Q).");
        }
        Integer max = call.arguments().has("maxResults") ? call.arguments().path("maxResults").asInt() : null;
        List<ProjectSearchHit> hits = projects.search(project.userId(), project.projectId(), query, max);
        ObjectNode output = objectMapper.createObjectNode(); output.put("projectId", project.projectId());
        output.put("projectVersion", project.manifest().version()); output.put("query", query); output.put("trust", "UNTRUSTED");
        var items = output.putArray("hits"); for (ProjectSearchHit hit : hits) { ObjectNode item = items.addObject(); evidence(item, project, hit.path(), hit.sha256()); item.put("lineNumber", hit.lineNumber()); item.put("parserVersion", PARSER_VERSION); item.put("line", hit.line()); }
        String version = hits.isEmpty() ? "no-hit" : hits.get(0).sha256();
        if (!hits.isEmpty()) evidence(output, project, hits.get(0).path(), version);
        return success(call, output, project.projectId(), "search:" + query, version);
    }

    private boolean isLiteralQuery(String query) {
        return query.chars().noneMatch(ch -> Character.isISOControl(ch)
                || ch == '*' || ch == '?' || ch == '[' || ch == ']');
    }
}
