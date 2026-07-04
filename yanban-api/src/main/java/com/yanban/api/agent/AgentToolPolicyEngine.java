package com.yanban.api.agent;

import com.yanban.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgentToolPolicyEngine {

    private static final String SEARCH_WEB = "search_web";
    private static final String SEARCH_LITERATURE = "search_literature";
    private static final String SEARCH_KNOWLEDGE = "search_knowledge";

    private final ToolRegistry toolRegistry;

    public AgentToolPolicyEngine(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public Decision decide(String userMessage, boolean ragDisabled, Set<String> skillAllowedTools) {
        Set<String> registeredTools = toolRegistry.listToolNames();
        if (skillAllowedTools != null && !skillAllowedTools.isEmpty()) {
            List<String> allowed = skillAllowedTools.stream()
                    .filter(registeredTools::contains)
                    .distinct()
                    .toList();
            return new Decision(allowed, Math.max(1, Math.min(3, allowed.size())), 1, "skill_allowlist");
        }

        String normalized = normalize(userMessage);
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        boolean literatureIntent = containsAny(normalized,
                "\u8bba\u6587", "\u6587\u732e", "\u671f\u520a", "doi", "arxiv", "bibtex", "openalex", "scholar",
                "paper", "literature", "citation", "bibliography");
        boolean lookupTokenIntent = !ragDisabled && containsAny(normalized,
                "_lookup", "lookup_", "mentor_lookup", "weekly_meeting", "lab_location", "weekly_deadline",
                "file_type_lookup", "paper_final_step", "rag_quality_metric", "rag_architecture", "rerank_policy");
        boolean knowledgeIntent = !ragDisabled && (lookupTokenIntent || containsAny(normalized,
                "\u77e5\u8bc6\u5e93", "\u4e0a\u4f20", "\u4e0a\u4f20\u7684", "\u6587\u6863",
                "\u6211\u7684\u8d44\u6599", "\u79c1\u6709\u8d44\u6599", "\u9879\u76ee\u8d44\u6599",
                "\u6839\u636e\u8d44\u6599", "\u6839\u636e\u6587\u6863", "\u5bfc\u5e08", "\u7ec4\u4f1a",
                "\u5b9e\u9a8c\u5ba4", "knowledge base", "uploaded", "my document"));
        boolean explicitSearch = containsAny(normalized,
                "\u641c\u7d22", "\u68c0\u7d22", "\u67e5\u4e00\u4e0b", "\u67e5\u627e", "\u627e\u4e00\u4e0b",
                "\u8054\u7f51", "\u6d4f\u89c8\u7f51\u9875", "\u7f51\u4e0a", "\u6765\u6e90", "\u5f15\u7528",
                "\u94fe\u63a5", "\u7f51\u5740", "\u5b98\u7f51", "\u5b98\u65b9", "\u6838\u5b9e", "\u9a8c\u8bc1",
                "search", "browse", "look up", "verify", "cite", "source", "url", "official");
        boolean currentIntent = containsAny(normalized,
                "\u6700\u65b0", "\u5f53\u524d", "\u73b0\u5728", "\u6700\u8fd1", "\u4eca\u65e5", "\u4eca\u5929",
                "\u5b9e\u65f6", "\u65b0\u95fb", "\u53d1\u5e03", "\u4ef7\u683c", "\u653f\u7b56", "\u6cd5\u89c4",
                "\u7248\u672c", "\u6a21\u578b\u5217\u8868",
                "latest", "current", "recent", "today", "now", "news", "released", "price", "pricing");
        boolean healthIntent = containsAny(normalized,
                "\u533b\u5b66", "\u533b\u7597", "\u5065\u5eb7", "\u75be\u75c5", "\u75c5\u56e0", "\u53d1\u75c5",
                "\u53d1\u75c5\u673a\u5236", "\u75c7\u72b6", "\u8bca\u65ad", "\u6cbb\u7597",
                "\u836f\u7269", "\u764c", "\u8840\u764c", "\u767d\u8840\u75c5", "\u7cd6\u5c3f\u75c5",
                "medical", "medicine", "health", "disease", "symptom", "diagnosis", "treatment",
                "diabetes", "leukemia", "cancer", "pathogenesis", "mechanism");

        addIfRegistered(allowed, registeredTools, knowledgeIntent, SEARCH_KNOWLEDGE);
        addIfRegistered(allowed, registeredTools, literatureIntent, SEARCH_LITERATURE);
        addIfRegistered(allowed, registeredTools, (explicitSearch || currentIntent || healthIntent) && !literatureIntent, SEARCH_WEB);

        int maxToolCalls = allowed.isEmpty()
                ? 0
                : allowed.contains(SEARCH_WEB) && allowed.size() == 1 ? 1 : 2;
        return new Decision(List.copyOf(allowed), maxToolCalls, 1, reason(allowed, explicitSearch, currentIntent, literatureIntent, knowledgeIntent, healthIntent));
    }

    private void addIfRegistered(Set<String> allowed, Set<String> registeredTools, boolean condition, String toolName) {
        if (condition && registeredTools.contains(toolName)) {
            allowed.add(toolName);
        }
    }

    private String reason(Set<String> allowed,
                          boolean explicitSearch,
                          boolean currentIntent,
                          boolean literatureIntent,
                          boolean knowledgeIntent,
                          boolean healthIntent) {
        if (allowed.isEmpty()) {
            return "direct_answer_no_tools";
        }
        List<String> reasons = new ArrayList<>();
        if (explicitSearch) {
            reasons.add("explicit_search");
        }
        if (currentIntent) {
            reasons.add("current_or_time_sensitive");
        }
        if (literatureIntent) {
            reasons.add("literature_intent");
        }
        if (knowledgeIntent) {
            reasons.add("knowledge_intent");
        }
        if (healthIntent) {
            reasons.add("health_or_medical");
        }
        return String.join("+", reasons);
    }

    private String normalize(String text) {
        return StringUtils.hasText(text) ? text.trim().toLowerCase(Locale.ROOT) : "";
    }

    private boolean containsAny(String text, String... needles) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public record Decision(
            List<String> allowedTools,
            int maxToolCalls,
            int maxDuplicateToolCalls,
            String reason
    ) {
    }
}
