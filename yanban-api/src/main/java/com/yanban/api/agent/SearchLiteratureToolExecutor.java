package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import com.yanban.paper.literature.AdHocLiteratureSearchService;
import com.yanban.paper.literature.AdHocLiteratureSearchService.AdHocLiteratureItem;
import com.yanban.paper.literature.AdHocLiteratureSearchService.AdHocLiteratureSearchResult;
import org.springframework.stereotype.Component;

@Component
public class SearchLiteratureToolExecutor implements ToolExecutor {

    private final AdHocLiteratureSearchService literatureSearchService;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public SearchLiteratureToolExecutor(AdHocLiteratureSearchService literatureSearchService, ObjectMapper objectMapper) {
        this.literatureSearchService = literatureSearchService;
        this.objectMapper = objectMapper;
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("query").put("type", "string").put("description", "文献检索主题、关键词或自然语言问题");
        properties.putObject("topK").put("type", "integer").put("description", "返回文献条数，默认 8，最大 20");
        properties.putObject("yearFrom").put("type", "integer").put("description", "可选起始年份，例如 2020");
        properties.putObject("includeBibtex").put("type", "boolean").put("description", "是否返回 BibTeX，默认 true");
        parameters.putArray("required").add("query");
        this.definition = new ToolDefinition("search_literature", "检索公开学术文献并返回题名、来源、DOI/arXiv、相关性说明和可选 BibTeX", parameters);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = call.arguments() == null ? null : call.arguments().path("query").asText(null);
        int topK = call.arguments() != null && call.arguments().has("topK") ? call.arguments().path("topK").asInt(8) : 8;
        Integer yearFrom = call.arguments() != null && call.arguments().has("yearFrom") ? call.arguments().path("yearFrom").asInt() : null;
        boolean includeBibtex = call.arguments() == null || !call.arguments().has("includeBibtex") || call.arguments().path("includeBibtex").asBoolean(true);
        AdHocLiteratureSearchResult result = literatureSearchService.search(query, Math.min(Math.max(topK, 1), 20), yearFrom);
        ObjectNode output = objectMapper.createObjectNode();
        output.put("query", result.query());
        output.put("rawCandidateCount", result.rawCandidateCount());
        output.put("uniqueCandidateCount", result.uniqueCandidateCount());
        output.put("sourceAttempts", result.sourceAttempts());
        ArrayNode failures = output.putArray("sourceFailures");
        result.sourceFailures().forEach(failures::add);
        ArrayNode items = output.putArray("items");
        for (AdHocLiteratureItem item : result.items()) {
            ObjectNode node = items.addObject();
            node.put("title", item.title());
            ArrayNode authors = node.putArray("authors");
            item.authors().forEach(authors::add);
            if (item.year() != null) node.put("year", item.year());
            if (item.venue() != null) node.put("venue", item.venue());
            if (item.doi() != null) node.put("doi", item.doi());
            if (item.arxivId() != null) node.put("arxivId", item.arxivId());
            if (item.openAlexId() != null) node.put("openAlexId", item.openAlexId());
            if (item.url() != null) node.put("url", item.url());
            node.put("source", item.source());
            node.put("score", item.score());
            if (item.abstractText() != null) node.put("abstract", abbreviate(item.abstractText(), 600));
            if (includeBibtex) node.put("bibtex", item.bibtex());
        }
        return ToolResult.success(call.id(), definition.name(), output);
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
