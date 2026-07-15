package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.research.CrossMaterialLinkItem;
import com.yanban.core.research.LiteralMatchItem;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ResearchEvidenceRef;
import com.yanban.core.research.ResearchToolErrorCode;
import com.yanban.core.research.ResearchToolOutcome;
import com.yanban.core.research.UntrustedResearchContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Literal deterministic cross-material matching over already-authorized manifest paths. */
@Component
public final class ProjectCrossMaterialSearchToolExecutor extends AbstractResearchProjectToolExecutor {
    private static final String PARSER = "cross-material-search@1";

    public ProjectCrossMaterialSearchToolExecutor(ProjectService projects, ObjectMapper objectMapper) { super("project_cross_material_search", projects, objectMapper); }

    @Override protected ResearchToolOutcome analyze(ResearchContext context, JsonNode arguments) {
        boolean scoped = arguments.has("relativePaths") && arguments.path("relativePaths").isArray()
                && !arguments.path("relativePaths").isEmpty();
        long manifestBytes = context.manifestFiles().stream().mapToLong(ProjectFileEntry::sizeBytes).sum();
        if (!scoped && manifestBytes > contract().budget().maxBytesInspected()) {
            throw new com.yanban.core.research.ResearchContractException(
                    ResearchToolErrorCode.INVALID_ARGUMENT,
                    "Large Project requires relativePaths for project_cross_material_search: "
                            + "call project_manifest, select concrete relevant files, then retry with "
                            + "{\"query\":\"literal concept\",\"relativePaths\":[\"paper.tex\",\"code.py\"],\"maxMatches\":10}. "
                            + "Do not retry the whole-Project scope.");
        }
        Map<ProjectRelativePath, ProjectFileEntry> files = requestedFiles(context, arguments, true);
        int maxMatches = arguments.path("maxMatches").asInt(20); String query = arguments.path("query").asText();
        boolean caseSensitive = arguments.path("caseSensitive").asBoolean(false);
        List<ResearchEvidenceRef> matches = new ArrayList<>(); List<String> snippets = new ArrayList<>(); long bytes = 0; boolean truncated = false;
        boolean partial = files.size() < context.manifestFiles().size() && !arguments.has("relativePaths");
        for (Map.Entry<ProjectRelativePath, ProjectFileEntry> entry : files.entrySet()) {
            if (bytes + entry.getValue().sizeBytes() > contract().budget().maxBytesInspected()) {
                if (matches.isEmpty()) throw new com.yanban.core.research.ResearchContractException(ResearchToolErrorCode.BUDGET_EXCEEDED,
                        "cross-material input exceeds byte budget");
                truncated = true; break;
            }
            // A manifest-listed file that disappears is a stale attestation, not a skippable
            // format issue. The shared read boundary converts it to INDEX_STALE/CONFLICT.
            ProjectFileResponse file = read(context, entry.getKey());
            bytes += utf8Bytes(file); String[] lines = file.content().split("\\R", -1);
            String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
            for (int line = 0; line < lines.length; line++) if ((caseSensitive ? lines[line]
                    : lines[line].toLowerCase(Locale.ROOT)).contains(needle)) {
                matches.add(ResearchToolSupport.evidence(context, entry.getKey(), entry.getValue(), line + 1, line + 1, PARSER));
                snippets.add(lines[line]); if (matches.size() >= maxMatches) { truncated = true; break; }
            }
            if (truncated) break;
        }
        if (matches.isEmpty())
            return ResearchToolSupport.outcome(List.of(), List.of(), partial, false, usage(files.size(), 0, 0, bytes));
        int distinctFiles = matches.stream().map(match -> match.relativePath().value())
                .collect(java.util.stream.Collectors.toSet()).size();
        if (distinctFiles < 2) {
            List<LiteralMatchItem> items = new ArrayList<>();
            for (int index = 0; index < matches.size(); index++) {
                ResearchEvidenceRef match = matches.get(index);
                items.add(new LiteralMatchItem(query, match.relativePath(), match.range().startLine(),
                        new UntrustedResearchContent(snippets.get(index), match)));
            }
            return ResearchToolSupport.outcome(items, matches, partial || files.size() > 1, truncated,
                    usage(files.size(), items.size(), matches.size(), bytes));
        }
        List<ResearchEvidenceRef> linked = List.copyOf(matches); ResearchEvidenceRef anchor = linked.get(0);
        CrossMaterialLinkItem item = new CrossMaterialLinkItem(query, "literal-match-across-authorized-material", linked,
                new UntrustedResearchContent(snippets.get(0), anchor));
        return ResearchToolSupport.outcome(List.of(item), linked, partial, truncated, usage(files.size(), 1, linked.size(), bytes));
    }
}
