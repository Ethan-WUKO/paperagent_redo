package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.harness.HarnessRequest;
import com.yanban.core.harness.ToolResultPostProcessor;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.tool.ToolResult;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SearchResultSynthesisPostProcessor implements ToolResultPostProcessor {

    private static final Set<String> SEARCH_TOOLS = Set.of("search_web", "search_literature", "search_knowledge");
    private static final int MAX_SYNTHESIS_INPUT_CHARS = 12000;
    private static final int FALLBACK_TOP_K = 5;

    private final ChatModelProvider modelProvider;
    private final ObjectMapper objectMapper;

    public SearchResultSynthesisPostProcessor(@Qualifier("chatModelProvider") ChatModelProvider modelProvider,
                                              ObjectMapper objectMapper) {
        this.modelProvider = modelProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolResult process(ToolResult result, HarnessRequest request) {
        if (result == null || !result.success() || result.output() == null || !SEARCH_TOOLS.contains(result.toolName())) {
            return result;
        }
        if ("search_result_synthesizer".equals(result.output().path("subAgent").asText())) {
            return result;
        }
        try {
            ChatResponse response = modelProvider.chat(new ChatRequest(
                    request.provider(),
                    request.model(),
                    List.of(
                            ChatMessage.system(systemPrompt()),
                            ChatMessage.user(userPrompt(result, request))
                    ),
                    0.0,
                    1800,
                    null,
                    request.apiKey(),
                    request.apiUrl(),
                    null,
                    null,
                    request.traceId()
            ));
            String content = response == null || response.message() == null ? null : response.message().content();
            if (!StringUtils.hasText(content)) {
                return fallback(result, request, "empty_sub_agent_response");
            }
            return ToolResult.success(result.toolCallId(), result.toolName(), normalizeSynthesis(result, request, content));
        } catch (Exception ex) {
            String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
            return fallback(result, request, abbreviate(message, 500));
        }
    }

    private String systemPrompt() {
        return """
                You are an ephemeral Search Synthesis sub-agent.
                Your only job is to inspect retrieved search candidates, select the best top-k evidence, rank it, and produce a concise conclusion for the parent agent.
                Do not call tools. Do not invent URLs, paper metadata, filenames, or citations.
                For public web results, prioritize sourceAuthority=official or primary_technical. Treat secondary/community/media sources as background only.
                For latest/current claims, do not use a low-authority source as the sole support for a vendor release, model version, date, or product capability.
                If the search result is degraded or empty, say so and provide a cautious fallback conclusion from model knowledge only when useful.
                Return JSON only with this schema:
                {
                  "query": "search query",
                  "degraded": false,
                  "conclusion": "short synthesized conclusion",
                  "selectedTopK": [
                    {
                      "rank": 1,
                      "title": "result title or filename",
                      "source": "url, DOI/arXiv/source, or knowledge filename",
                      "sourceAuthority": "official|primary_technical|general_web|secondary|unknown",
                      "evidence": "specific snippet or fact",
                      "reason": "why this result was selected"
                    }
                  ],
                  "limitations": []
                }
                """;
    }

    private String userPrompt(ToolResult result, HarnessRequest request) throws Exception {
        return "Parent task:\n"
                + request.userMessage()
                + "\n\nSource tool: "
                + result.toolName()
                + "\n\nRaw search tool output JSON:\n"
                + abbreviate(objectMapper.writeValueAsString(result.output()), MAX_SYNTHESIS_INPUT_CHARS);
    }

    private JsonNode normalizeSynthesis(ToolResult result, HarnessRequest request, String raw) throws Exception {
        JsonNode parsed = objectMapper.readTree(stripCodeFence(raw));
        ObjectNode output = objectMapper.createObjectNode();
        output.put("subAgent", "search_result_synthesizer");
        output.put("sourceTool", result.toolName());
        output.put("query", firstTextOrDefault(parsed, queryFromOriginal(result, request), "query", "searchQuery", "question"));
        output.put("degraded", result.output().path("degraded").asBoolean(false) || parsed.path("degraded").asBoolean(false));
        output.put("conclusion", firstText(parsed, "conclusion", "summary", "answer", ""));
        JsonNode selected = parsed.path("selectedTopK");
        if (!selected.isArray()) {
            selected = parsed.path("selected_top_k");
        }
        if (!selected.isArray()) {
            selected = fallbackSelectedItems(result.output(), FALLBACK_TOP_K);
        }
        output.set("selectedTopK", selected);
        JsonNode limitations = parsed.path("limitations");
        output.set("limitations", limitations.isArray() ? limitations : objectMapper.createArrayNode());
        output.put("guidance", "Parent agent should use this synthesized search result instead of the raw candidate list.");
        return output;
    }

    private ToolResult fallback(ToolResult result, HarnessRequest request, String reason) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("subAgent", "search_result_synthesizer");
        output.put("sourceTool", result.toolName());
        output.put("query", queryFromOriginal(result, request));
        output.put("degraded", true);
        output.put("conclusion", "Search synthesis sub-agent could not complete ranking/summarization. Use the selected candidates cautiously and state this limitation.");
        output.put("error", reason);
        output.set("selectedTopK", fallbackSelectedItems(result.output(), FALLBACK_TOP_K));
        ArrayNode limitations = output.putArray("limitations");
        limitations.add("Search synthesis failed or was unavailable.");
        output.put("guidance", "Parent agent may continue from these candidates, but should mention the limitation and avoid fabricating sources.");
        return ToolResult.success(result.toolCallId(), result.toolName(), output);
    }

    private ArrayNode fallbackSelectedItems(JsonNode original, int limit) {
        ArrayNode selected = objectMapper.createArrayNode();
        JsonNode items = original == null ? null : original.path("items");
        if (items == null || !items.isArray()) {
            return selected;
        }
        int rank = 1;
        for (JsonNode item : items) {
            if (rank > limit) {
                break;
            }
            ObjectNode node = selected.addObject();
            node.put("rank", rank++);
            node.put("title", firstTextOrDefault(item, "untitled", "title", "filename", "name"));
            node.put("source", firstText(item, "url", "doi", "arxivId", "openAlexId", "filename", "source"));
            node.put("sourceAuthority", firstTextOrDefault(item, "unknown", "sourceAuthority"));
            node.put("authorityScore", item.path("authorityScore").isNumber() ? item.path("authorityScore").asDouble() : 0.0);
            node.put("evidence", abbreviate(firstText(item, "snippet", "abstract", "chunkText", "reason", ""), 500));
            node.put("reason", "Selected by source credibility and retrieval order from retrieved candidates.");
        }
        return selected;
    }

    private String queryFromOriginal(ToolResult result, HarnessRequest request) {
        String query = result.output() == null ? "" : result.output().path("query").asText("");
        return StringUtils.hasText(query) ? query : request.userMessage();
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private String firstTextOrDefault(JsonNode node, String fallback, String... fields) {
        String value = firstText(node, fields);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String stripCodeFence(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        cleaned = cleaned.replaceAll("(?s)^```json\\s*", "")
                .replaceAll("(?s)^```\\s*", "")
                .replaceAll("(?s)```$", "")
                .trim();
        int first = cleaned.indexOf('{');
        int last = cleaned.lastIndexOf('}');
        if (first >= 0 && last >= first) {
            return cleaned.substring(first, last + 1).trim();
        }
        return cleaned;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
