package com.yanban.core.research;

/** One deterministic literal source match with an exact Project-relative provenance anchor. */
public record LiteralMatchItem(String query, ProjectRelativePath relativePath, int lineNumber,
                               UntrustedResearchContent content) implements ResearchToolItem {
    public LiteralMatchItem {
        if (query == null || query.isBlank() || relativePath == null || lineNumber < 1 || content == null) {
            throw new IllegalArgumentException("literal match requires query, relative path, line number, and content");
        }
    }
    @Override public ResearchToolItemType itemType() { return ResearchToolItemType.LITERAL_MATCH; }
}
