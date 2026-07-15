package com.yanban.api.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PlanningAgentPlanner {

    private static final int DEFAULT_MAX_PLAN_STEPS = 6;
    private static final int DEFAULT_MAX_RECOVERY_STEPS = 3;
    private static final int PLANNER_MAX_TOKENS = 3072;
    private static final int PLANNER_RETRY_MAX_TOKENS = 2048;
    private static final int COMPACT_RETRY_MAX_STEPS = 4;
    private static final int RECOVERY_PLANNER_MAX_TOKENS = 1024;
    private static final int MAX_FAILURE_DIAGNOSTIC_LENGTH = 500;

    private final ChatModelProvider modelProvider;
    private final ObjectMapper objectMapper;

    public PlanningAgentPlanner(@Qualifier("chatModelProvider") ChatModelProvider modelProvider,
                                ObjectMapper objectMapper) {
        this.modelProvider = modelProvider;
        this.objectMapper = objectMapper;
    }

    public PlanSpec createPlan(String goal,
                               String provider,
                               String model,
                               String apiKey,
                               String apiUrl,
                               String skillPrompt,
                               List<String> skillAllowedTools) {
        PlannerAttempt first = requestPlan(
                goal, provider, model, apiKey, apiUrl,
                buildPlannerSystemPrompt(skillPrompt, skillAllowedTools),
                "Create an executable plan for this user task:\n" + goal,
                PLANNER_MAX_TOKENS, DEFAULT_MAX_PLAN_STEPS, skillAllowedTools, "Planner");
        if (first.spec().executable() || !first.retryable()) {
            return first.spec();
        }

        PlannerAttempt second = requestPlan(
                goal, provider, model, apiKey, apiUrl,
                buildCompactRepairPrompt(skillPrompt, skillAllowedTools, first.spec().failureCode()),
                "Create a fresh, complete replacement plan for this user task:\n" + goal,
                PLANNER_RETRY_MAX_TOKENS, COMPACT_RETRY_MAX_STEPS, skillAllowedTools, "Planner retry");
        if (second.spec().executable()) {
            return second.spec();
        }
        PlannerFailureCode finalCode = second.spec().failureCode() == null
                ? PlannerFailureCode.INVALID_PLAN : second.spec().failureCode();
        String diagnostic = "Planner failed after one bounded retry [first="
                + first.spec().failureCode() + ", second=" + finalCode + "]: "
                + abbreviate(second.spec().failureMessage(), 300);
        return PlanSpec.failure(finalCode, abbreviate(diagnostic, MAX_FAILURE_DIAGNOSTIC_LENGTH));
    }

    private PlannerAttempt requestPlan(String goal,
                                       String provider,
                                       String model,
                                       String apiKey,
                                       String apiUrl,
                                       String systemPrompt,
                                       String userPrompt,
                                       int maxTokens,
                                       int maxSteps,
                                       List<String> skillAllowedTools,
                                       String attemptLabel) {
        ChatResponse response;
        try {
            response = modelProvider.chat(structuredJsonRequest(
                    provider,
                    model,
                    List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userPrompt)),
                    0.2,
                    maxTokens,
                    apiKey,
                    apiUrl
            ));
        } catch (RuntimeException ex) {
            return PlannerAttempt.of(PlanSpec.failure(PlannerFailureCode.MODEL_CALL_FAILED,
                    attemptLabel + " model call failed: " + abbreviate(ex.getMessage(), 300)));
        }

        String content = response == null || response.message() == null ? null : response.message().content();
        if (!StringUtils.hasText(content)) {
            return PlannerAttempt.of(PlanSpec.failure(PlannerFailureCode.EMPTY_RESPONSE,
                    attemptLabel + " returned an empty plan" + finishReasonSuffix(response) + "."));
        }
        if (indicatesTruncation(response == null ? null : response.finishReason())) {
            return PlannerAttempt.of(PlanSpec.failure(PlannerFailureCode.INVALID_PLAN,
                    attemptLabel + " output was truncated by the model" + finishReasonSuffix(response) + "."));
        }
        try {
            return PlannerAttempt.of(parsePlan(goal, content, maxSteps, skillAllowedTools));
        } catch (PlannerFailureException ex) {
            return PlannerAttempt.of(PlanSpec.failure(ex.code, abbreviate(ex.getMessage(), 300)));
        } catch (Exception ex) {
            return PlannerAttempt.of(PlanSpec.failure(PlannerFailureCode.INVALID_PLAN,
                    attemptLabel + " JSON parse failed: " + jsonParseDiagnostic(ex)));
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
            return PlanSpec.failure(PlannerFailureCode.MODEL_CALL_FAILED,
                    "Recovery planner model call failed: " + abbreviate(ex.getMessage(), 300));
        }

        String content = response == null || response.message() == null ? null : response.message().content();
        if (!StringUtils.hasText(content)) {
            return PlanSpec.failure(PlannerFailureCode.EMPTY_RESPONSE, "Recovery planner returned an empty plan.");
        }
        try {
            return parsePlan(goal, content, DEFAULT_MAX_RECOVERY_STEPS, skillAllowedTools);
        } catch (PlannerFailureException ex) {
            return PlanSpec.failure(ex.code, ex.getMessage());
        } catch (Exception ex) {
            return PlanSpec.failure(PlannerFailureCode.INVALID_PLAN,
                    "Recovery plan JSON parse failed: " + jsonParseDiagnostic(ex));
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
                1. Use the fewest steps needed: 1-3 for simple tasks and at most 6 for complex tasks.
                2. Use dependencies only when a step truly needs another step's result. Independent research steps should not depend on each other.
                3. Each description must be directly executable and say what to read, search, call, compare, synthesize, or verify.
                4. Do not invent tools. allowedTools may only contain the tools exposed below. It is an explicit per-step allowlist:
                   use [] when the step must not receive any tool, and list only the tools needed by a tool-using step.
                5. For public web, current facts, official docs, products, news, or broad internet research, use search_web.
                6. Use recommend_literature for academic papers, DOI, arXiv, OpenAlex, BibTeX, scholarly literature, survey building, classic-paper collection, or citation recommendation.
                7. Use search_knowledge only for user-uploaded/private knowledge base or project-local knowledge.
                8. Avoid repeated search-only loops. Prefer search, synthesis, and verification steps.
                9. If search may fail or return degraded results, plan a fallback using model knowledge with a clear limitation note.
                10. Keep JSON compact: summary <= 120 characters, title <= 80, description <= 240, and successCriteria <= 160.
                    Do not repeat the goal or tool documentation inside step fields.
                11. Search and audit success criteria must require reporting the governed result, including an explicit
                    zero-match outcome; never require the data to contain a positive match that may not exist. Require
                    file paths and line numbers for actual matches only, never for a zero-match outcome.

                Tools exposed to this plan:
                """)
                .append(String.join(", ", skillAllowedTools == null ? List.of() : skillAllowedTools))
                .append("\n");
        if (skillAllowedTools != null) {
            sb.append("\nResolved plan tool allowlist:\n")
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

    private String buildCompactRepairPrompt(String skillPrompt,
                                            List<String> skillAllowedTools,
                                            PlannerFailureCode firstFailureCode) {
        StringBuilder prompt = new StringBuilder("""
                You are repairing a failed plan generation. Return exactly one compact JSON object and nothing else.
                Do not quote or repair the previous fragment; generate a fresh replacement from the user goal.
                Use only this shape:
                {"summary":"...","steps":[{"id":"s1","type":"ANALYSIS","title":"...","description":"...","deps":[],"tools":[],"success":"..."}]}
                Use 1-4 short steps. Each string must be one short sentence: summary <=80, title <=40,
                description <=120, success <=80 characters. Do not emit any other keys, Markdown, commentary,
                or repeated goal/tool documentation. deps contains prior step ids; tools must be selected only
                from the exact resolved allowlist below. Never add write/command tools to a Project read-only plan.
                Resolved allowlist: """);
        prompt.append(String.join(", ", skillAllowedTools == null ? List.of() : skillAllowedTools))
                .append("\nThe first attempt failed with code ").append(firstFailureCode).append(".\n");
        if (StringUtils.hasText(skillPrompt)) {
            prompt.append("Keep these active skill constraints:\n").append(skillPrompt.trim()).append("\n");
        }
        return prompt.toString();
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
                .append("Tools exposed to this recovery plan:\n")
                .append(String.join(", ", skillAllowedTools == null ? List.of() : skillAllowedTools))
                .append("\n");
        if (skillAllowedTools != null) {
            sb.append("\nResolved plan tool allowlist: ")
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

    private PlanSpec parsePlan(String goal, String raw, int maxSteps, List<String> exposedToolNames) throws Exception {
        String cleaned = stripCodeFence(raw);
        JsonNode root = objectMapper.readTree(cleaned);
        if (root == null || !root.isObject()) {
            throw new PlannerFailureException(PlannerFailureCode.INVALID_PLAN, "Planner output must be a JSON object.");
        }
        String summary = abbreviate(textOrDefault(
                root.path("summary"), "Execute task: " + abbreviate(goal, 80)), 120);
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            stepsNode = root.path("tasks");
        }
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new PlannerFailureException(PlannerFailureCode.NO_STEPS,
                    "Planner output did not contain an executable steps/tasks array.");
        }

        int limit = Math.min(stepsNode.size(), maxSteps);
        Map<String, String> idMapping = new LinkedHashMap<>();
        for (int i = 0; i < limit; i++) {
            JsonNode node = stepsNode.get(i);
            if (node == null || !node.isObject()) {
                throw new PlannerFailureException(PlannerFailureCode.INVALID_PLAN,
                        "Planner step " + (i + 1) + " must be a JSON object.");
            }
            String originalId = textOrDefault(node.path("id"), "step_" + (i + 1));
            idMapping.put(originalId, "step_" + (i + 1));
        }

        List<StepSpec> steps = new ArrayList<>();
        Set<String> registeredTools = exposedToolNames == null ? Set.of() : Set.copyOf(exposedToolNames);
        for (int i = 0; i < limit; i++) {
            JsonNode node = stepsNode.get(i);
            if (node == null || !node.isObject()) {
                throw new PlannerFailureException(PlannerFailureCode.INVALID_PLAN,
                        "Planner step " + (i + 1) + " must be a JSON object.");
            }
            String stepId = "step_" + (i + 1);
            String title = abbreviate(
                    textOrDefault(node.path("title"), textOrDefault(node.path("name"), "Step " + (i + 1))), 80);
            String description = abbreviate(textOrDefault(node.path("description"), title), 240);
            String type = normalizeType(textOrDefault(node.path("type"), "ANALYSIS"));
            List<String> dependencies = normalizeDependencies(node.path("dependencies"), idMapping, stepId);
            if (dependencies.isEmpty() && node.path("deps").isArray()) {
                dependencies = normalizeDependencies(node.path("deps"), idMapping, stepId);
            }
            List<String> allowedTools = normalizeAllowedTools(node.path("allowedTools"), registeredTools);
            if (!node.has("allowedTools")) {
                allowedTools = normalizeAllowedTools(node.path("allowed_tools"), registeredTools);
            }
            if (!node.has("allowedTools") && !node.has("allowed_tools")) {
                allowedTools = normalizeAllowedTools(node.path("tools"), registeredTools);
            }
            String successCriteria = abbreviate(textOrDefault(
                    node.path("successCriteria"),
                    textOrDefault(node.path("success_criteria"),
                            textOrDefault(node.path("success"), "The step goal is completed with a verifiable result."))
            ), 160);
            steps.add(new StepSpec(stepId, title, description, type, dependencies, allowedTools, successCriteria));
        }
        return new PlanSpec(summary, steps, cleaned);
    }

    private boolean indicatesTruncation(String finishReason) {
        if (!StringUtils.hasText(finishReason)) {
            return false;
        }
        String normalized = finishReason.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("length")
                || normalized.contains("max_token")
                || normalized.contains("token_limit")
                || normalized.contains("output_limit");
    }

    private String finishReasonSuffix(ChatResponse response) {
        String finishReason = response == null ? null : response.finishReason();
        return StringUtils.hasText(finishReason)
                ? " (finishReason=" + abbreviate(finishReason, 40) + ")"
                : "";
    }

    private String jsonParseDiagnostic(Exception exception) {
        if (exception instanceof JsonProcessingException jsonException) {
            var location = jsonException.getLocation();
            return jsonException.getClass().getSimpleName()
                    + (location == null ? "" : " at line " + location.getLineNr()
                    + ", column " + location.getColumnNr());
        }
        return exception.getClass().getSimpleName() + ": " + abbreviate(exception.getMessage(), 160);
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

    public record PlanSpec(String summary, List<StepSpec> steps, String rawJson,
                           PlannerFailureCode failureCode, String failureMessage) {
        public PlanSpec(String summary, List<StepSpec> steps, String rawJson) {
            this(summary, steps, rawJson, null, null);
        }

        public PlanSpec {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public static PlanSpec failure(PlannerFailureCode code, String message) {
            return new PlanSpec(null, List.of(), null, code, message);
        }

        public boolean executable() {
            return failureCode == null && !steps.isEmpty();
        }
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

    private record PlannerAttempt(PlanSpec spec) {
        private static PlannerAttempt of(PlanSpec spec) {
            return new PlannerAttempt(spec);
        }

        private boolean retryable() {
            return spec != null && (spec.failureCode() == PlannerFailureCode.EMPTY_RESPONSE
                    || spec.failureCode() == PlannerFailureCode.INVALID_PLAN);
        }
    }

    private static final class PlannerFailureException extends Exception {
        private final PlannerFailureCode code;

        private PlannerFailureException(PlannerFailureCode code, String message) {
            super(message);
            this.code = code;
        }
    }
}
