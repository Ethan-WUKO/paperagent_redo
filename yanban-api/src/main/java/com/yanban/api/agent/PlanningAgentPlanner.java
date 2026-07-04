package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PlanningAgentPlanner {

    private static final int DEFAULT_MAX_PLAN_STEPS = 10;
    private static final int DEFAULT_MAX_RECOVERY_STEPS = 3;
    private static final int PLANNER_MAX_TOKENS = 1536;
    private static final int RECOVERY_PLANNER_MAX_TOKENS = 1024;

    private final ChatModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public PlanningAgentPlanner(@Qualifier("chatModelProvider") ChatModelProvider modelProvider,
                                ToolRegistry toolRegistry,
                                ObjectMapper objectMapper) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    public PlanSpec createPlan(String goal,
                               String provider,
                               String model,
                               String apiKey,
                               String apiUrl,
                               String skillPrompt,
                               List<String> skillAllowedTools) {
        ChatResponse response;
        try {
            response = modelProvider.chat(structuredJsonRequest(
                    provider,
                    model,
                    List.of(
                            ChatMessage.system(buildPlannerSystemPrompt(skillPrompt, skillAllowedTools)),
                            ChatMessage.user("Create an executable plan for this user task:\n" + goal)
                    ),
                    0.2,
                    PLANNER_MAX_TOKENS,
                    apiKey,
                    apiUrl
            ));
        } catch (RuntimeException ex) {
            return fallbackPlan(goal, "Planner model call failed: " + abbreviate(ex.getMessage(), 300));
        }

        String content = response == null || response.message() == null ? null : response.message().content();
        if (!StringUtils.hasText(content)) {
            return fallbackPlan(goal, "Planner returned an empty plan.");
        }
        try {
            return parsePlan(goal, content, DEFAULT_MAX_PLAN_STEPS);
        } catch (Exception ex) {
            return fallbackPlan(goal, "Plan JSON parse failed: " + ex.getMessage()
                    + "\nRaw output: " + abbreviate(content, 1200));
        }
    }

    public PlanSpec createRecoveryPlan(String goal,
                                       String failedStepContext,
                                       String provider,
                                       String model,
                                       String apiKey,
                                       String apiUrl,
                                       String skillPrompt,
                                       List<String> skillAllowedTools) {
        ChatResponse response;
        try {
            response = modelProvider.chat(structuredJsonRequest(
                    provider,
                    model,
                    List.of(
                            ChatMessage.system(buildRecoveryPlannerSystemPrompt(skillPrompt, skillAllowedTools)),
                            ChatMessage.user("User goal:\n" + goal + "\n\nFailed execution context:\n" + failedStepContext)
                    ),
                    0.2,
                    RECOVERY_PLANNER_MAX_TOKENS,
                    apiKey,
                    apiUrl
            ));
        } catch (RuntimeException ex) {
            return null;
        }

        String content = response == null || response.message() == null ? null : response.message().content();
        if (!StringUtils.hasText(content)) {
            return null;
        }
        try {
            return parsePlan(goal, content, DEFAULT_MAX_RECOVERY_STEPS);
        } catch (Exception ex) {
            return null;
        }
    }

    private ChatRequest structuredJsonRequest(String provider,
                                              String model,
                                              List<ChatMessage> messages,
                                              Double temperature,
                                              Integer maxTokens,
                                              String apiKey,
                                              String apiUrl) {
        return new ChatRequest(
                provider,
                model,
                messages,
                temperature,
                maxTokens,
                null,
                apiKey,
                apiUrl,
                ChatRequest.ResponseFormat.jsonObject(),
                ChatRequest.Thinking.disabled(),
                null
        );
    }

    private String buildPlannerSystemPrompt(String skillPrompt, List<String> skillAllowedTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are the planner for Yanban Agent.
                Your job is to decompose a user task into an executable DAG plan. Do not execute the task.
                Return one JSON object only. Do not include Markdown fences, explanations, or extra text.

                Allowed step types: FILE_READ, FILE_WRITE, COMMAND, ANALYSIS, VERIFICATION, RAG, MCP, PAPER, TOOL.

                Output JSON schema:
                {
                  "summary": "short plan summary",
                  "steps": [
                    {
                      "id": "step_1",
                      "title": "short title",
                      "description": "specific task instruction for the executor agent",
                      "type": "ANALYSIS",
                      "dependencies": [],
                      "allowedTools": [],
                      "successCriteria": "observable completion criteria"
                    }
                  ]
                }

                Planning rules:
                1. Simple tasks should use 1-3 steps. Complex tasks should use 4-10 steps. Never exceed 10 steps.
                2. Use dependencies only when a step truly needs another step's result. Independent research steps should not depend on each other.
                3. Each description must be directly executable and say what to read, search, call, compare, synthesize, or verify.
                4. Do not invent tools. allowedTools may only contain registered tool names. Use [] when no tool restriction is needed.
                5. For public web, current facts, official docs, products, news, or broad internet research, use search_web.
                6. Use search_literature only for academic papers, DOI, arXiv, OpenAlex, BibTeX, or scholarly literature.
                7. Use search_knowledge only for user-uploaded/private knowledge base or project-local knowledge.
                8. Avoid repeated search-only loops. Prefer search, synthesis, and verification steps.
                9. If search may fail or return degraded results, plan a fallback using model knowledge with a clear limitation note.

                Registered tool names:
                """)
                .append(String.join(", ", toolRegistry.listToolNames()))
                .append("\n");
        if (skillAllowedTools != null && !skillAllowedTools.isEmpty()) {
            sb.append("\nActive skill tool allowlist:\n")
                    .append(String.join(", ", skillAllowedTools))
                    .append("\nSteps must not use tools outside this allowlist.\n");
        }
        if (StringUtils.hasText(skillPrompt)) {
            sb.append("\nActive skill instructions:\n")
                    .append(skillPrompt.trim())
                    .append("\n");
        }
        return sb.toString();
    }

    private String buildRecoveryPlannerSystemPrompt(String skillPrompt, List<String> skillAllowedTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the recovery planner for a Plan-and-Execute research agent.\n")
                .append("Create 1-").append(DEFAULT_MAX_RECOVERY_STEPS)
                .append(" executable recovery steps that can replace a failed step and let downstream work continue.\n")
                .append("Return one JSON object only. Do not include Markdown or explanations.\n")
                .append("Use the same JSON schema as the main planner: summary and steps[].\n")
                .append("Each recovery step must be specific, tool-aware, and focused on producing a reusable result.\n")
                .append("Do not depend on the failed step itself; the runtime will attach completed prerequisite dependencies.\n")
                .append("Registered tools:\n")
                .append(String.join(", ", toolRegistry.listToolNames()))
                .append("\n");
        if (skillAllowedTools != null && !skillAllowedTools.isEmpty()) {
            sb.append("\nSkill tool allowlist: ")
                    .append(String.join(", ", skillAllowedTools))
                    .append("\nRecovery steps must not use tools outside this allowlist.\n");
        }
        if (StringUtils.hasText(skillPrompt)) {
            sb.append("\nActive skill instructions:\n")
                    .append(skillPrompt.trim())
                    .append("\n");
        }
        return sb.toString();
    }

    private PlanSpec parsePlan(String goal, String raw, int maxSteps) throws Exception {
        String cleaned = stripCodeFence(raw);
        JsonNode root = objectMapper.readTree(cleaned);
        String summary = textOrDefault(root.path("summary"), "Execute task: " + abbreviate(goal, 80));
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            stepsNode = root.path("tasks");
        }
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            return fallbackPlan(goal, "Planner output did not contain a steps/tasks array.");
        }

        int limit = Math.min(stepsNode.size(), maxSteps);
        Map<String, String> idMapping = new LinkedHashMap<>();
        for (int i = 0; i < limit; i++) {
            JsonNode node = stepsNode.get(i);
            String originalId = textOrDefault(node.path("id"), "step_" + (i + 1));
            idMapping.put(originalId, "step_" + (i + 1));
        }

        List<StepSpec> steps = new ArrayList<>();
        Set<String> registeredTools = toolRegistry.listToolNames();
        for (int i = 0; i < limit; i++) {
            JsonNode node = stepsNode.get(i);
            String stepId = "step_" + (i + 1);
            String title = textOrDefault(node.path("title"), textOrDefault(node.path("name"), "Step " + (i + 1)));
            String description = textOrDefault(node.path("description"), title);
            String type = normalizeType(textOrDefault(node.path("type"), "ANALYSIS"));
            List<String> dependencies = normalizeDependencies(node.path("dependencies"), idMapping, stepId);
            List<String> allowedTools = normalizeAllowedTools(node.path("allowedTools"), registeredTools);
            if (allowedTools.isEmpty()) {
                allowedTools = normalizeAllowedTools(node.path("allowed_tools"), registeredTools);
            }
            String successCriteria = textOrDefault(
                    node.path("successCriteria"),
                    textOrDefault(node.path("success_criteria"), "The step goal is completed with a verifiable result.")
            );
            steps.add(new StepSpec(stepId, title, description, type, dependencies, allowedTools, successCriteria));
        }
        return new PlanSpec(summary, steps, cleaned);
    }

    private PlanSpec fallbackPlan(String goal, String reason) {
        StepSpec step = new StepSpec(
                "step_1",
                "Complete the user task directly",
                goal,
                "ANALYSIS",
                List.of(),
                List.of(),
                "The executor provides a direct useful result for the user task and uses tools when needed."
        );
        String raw = "{\"summary\":\"Planner fallback\",\"reason\":" + quote(reason) + "}";
        return new PlanSpec("Direct execution: " + abbreviate(goal, 80), List.of(step), raw);
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

    private List<String> normalizeDependencies(JsonNode node, Map<String, String> idMapping, String selfId) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String raw = item.asText(null);
                String mapped = idMapping.getOrDefault(raw, raw);
                if (StringUtils.hasText(mapped)
                        && !selfId.equals(mapped)
                        && idMapping.containsValue(mapped)
                        && !values.contains(mapped)) {
                    values.add(mapped);
                }
            }
        }
        return values;
    }

    private List<String> normalizeAllowedTools(JsonNode node, Set<String> registeredTools) {
        if (node == null || node.isMissingNode() || !node.isArray()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        ArrayNode array = (ArrayNode) node;
        for (JsonNode item : array) {
            String tool = item.asText(null);
            if (StringUtils.hasText(tool) && registeredTools.contains(tool)) {
                values.add(tool.trim());
            }
        }
        return List.copyOf(values);
    }

    private String normalizeType(String raw) {
        String normalized = raw == null ? "ANALYSIS" : raw.trim().toUpperCase();
        return switch (normalized) {
            case "FILE_READ", "FILE_WRITE", "COMMAND", "ANALYSIS", "VERIFICATION", "RAG", "MCP", "PAPER", "TOOL" -> normalized;
            default -> "ANALYSIS";
        };
    }

    private String textOrDefault(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.asText(null);
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String quote(String value) {
        try {
            return objectMapper.writeValueAsString(value == null ? "" : value);
        } catch (Exception ex) {
            return "\"\"";
        }
    }

    public record PlanSpec(String summary, List<StepSpec> steps, String rawJson) {
    }

    public record StepSpec(
            String id,
            String title,
            String description,
            String type,
            List<String> dependencies,
            List<String> allowedTools,
            String successCriteria
    ) {
    }
}
