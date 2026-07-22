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
    private static final int CANDIDATE_PROPOSAL_MIN_TOOL_CALLS = 3;

    private static final int DEFAULT_MAX_PLAN_STEPS = 6;
    private static final int DEFAULT_MAX_RECOVERY_STEPS = 3;
    private static final int PLANNER_MAX_TOKENS = 3072;
    private static final int PLANNER_RETRY_MAX_TOKENS = 2048;
    private static final int COMPACT_RETRY_MAX_STEPS = 4;
    private static final int RECOVERY_PLANNER_MAX_TOKENS = 1024;
    private static final int MAX_FAILURE_DIAGNOSTIC_LENGTH = 500;
    private static final int MAX_PLANNED_TOOL_CALLS_PER_STEP = 12;
    private static final int MAX_PLANNED_RUNTIME_STEPS_PER_STEP = 20;
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
        return createPlan(goal, provider, model, apiKey, apiUrl, skillPrompt, skillAllowedTools,
                AgentOrchestrationRequirements.empty());
    }

    public PlanSpec createPlan(String goal,
                               String provider,
                               String model,
                               String apiKey,
                               String apiUrl,
                               String skillPrompt,
                               List<String> skillAllowedTools,
                               AgentOrchestrationRequirements orchestrationRequirements) {
        return createPlan(goal, provider, model, apiKey, apiUrl, skillPrompt, skillAllowedTools,
                orchestrationRequirements, null);
    }

    public PlanSpec createPlan(String goal,
                               String provider,
                               String model,
                               String apiKey,
                               String apiUrl,
                               String skillPrompt,
                               List<String> skillAllowedTools,
                               AgentOrchestrationRequirements orchestrationRequirements,
                               String governedMemoryContext) {
        List<String> goalBoundTools = goalBoundTools(goal, skillAllowedTools);
        PlannerAttempt first = requestPlan(
                goal, provider, model, apiKey, apiUrl,
                buildPlannerSystemPrompt(skillPrompt, goalBoundTools, orchestrationRequirements,
                        governedMemoryContext),
                "Create an executable plan for this user task:\n" + goal,
                PLANNER_MAX_TOKENS, DEFAULT_MAX_PLAN_STEPS, goalBoundTools, "Planner");
        if (first.spec().executable() || !first.retryable()) {
            return first.spec();
        }

        PlannerAttempt second = requestPlan(
                goal, provider, model, apiKey, apiUrl,
                buildCompactRepairPrompt(skillPrompt, goalBoundTools, first.spec().failureCode(),
                        orchestrationRequirements, governedMemoryContext),
                "Create a fresh, complete replacement plan for this user task:\n" + goal,
                PLANNER_RETRY_MAX_TOKENS, COMPACT_RETRY_MAX_STEPS, goalBoundTools, "Planner retry");
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
            return PlannerAttempt.of(parsePlan(goal, content, maxSteps, skillAllowedTools, false));
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
        List<String> goalBoundTools = goalBoundTools(goal, skillAllowedTools);
        ChatResponse response;
        try {
            response = modelProvider.chat(structuredJsonRequest(
                    provider,
                    model,
                    List.of(
                            ChatMessage.system(buildRecoveryPlannerSystemPrompt(skillPrompt, goalBoundTools)),
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
            return parsePlan(goal, content, DEFAULT_MAX_RECOVERY_STEPS, goalBoundTools, true);
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

    private String buildPlannerSystemPrompt(String skillPrompt,
                                            List<String> skillAllowedTools,
                                            AgentOrchestrationRequirements orchestrationRequirements,
                                            String governedMemoryContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are the planner for Yanban Agent.
                Your job is to decompose a user task into an executable DAG plan. Do not execute the task.
                Return one JSON object only. Do not include Markdown fences, explanations, or extra text.

                Allowed step types: FILE_READ, FILE_WRITE, COMMAND, ANALYSIS, VERIFICATION, RAG, MCP, PAPER, TOOL, SANDBOX_EXECUTE.

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
                      "budget": {"maxToolCalls": 0, "maxRuntimeSteps": 4},
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
                12. Use SANDBOX_EXECUTE only when sandbox_execute appears in the resolved allowlist and the task explicitly
                    requires running, executing, compiling, building, or testing Project code. An explicit execution request
                    must include one sandbox_execute step even when it has only one target. Include exact Project-relative
                    input paths in the description. The server,
                    not this plan, selects the command profile and resources. Such a Plan always requires a later explicit
                    execution confirmation and can never auto-execute.
                13. project_propose_candidate is allowed only when the original user explicitly requests a modification,
                    patch, Candidate, or code change. A read, comparison, summary, matrix, report, or conclusion task is
                    read-only: use FILE_READ plus ANALYSIS/VERIFICATION and finish by answering the original requested
                    deliverable. Never invent a Candidate or code-generation step for a read-only task. A Candidate step
                    must reserve at least 3 tool calls so it can re-read exact evidence ranges before the final proposal.
                14. Every step must include a budget. maxToolCalls is 0 for a tool-free step and 1-12 for a
                    tool-using step. maxRuntimeSteps is 1-20. These are per-step ceilings only; the Runtime will
                    intersect them with the existing authoritative Plan, tool, time, confirmation and sandbox budgets.

                Tools exposed to this plan:
                """)
                .append(String.join(", ", skillAllowedTools == null ? List.of() : skillAllowedTools))
                .append("\n");
        if (skillAllowedTools != null) {
            sb.append("\nResolved plan tool allowlist:\n")
                    .append(String.join(", ", skillAllowedTools))
                    .append("\nSteps must not use tools outside this allowlist.\n");
        }
        appendConfirmedCandidatePlanConstraint(sb, skillAllowedTools);
        if (StringUtils.hasText(skillPrompt)) {
            sb.append("\nActive skill instructions:\n")
                    .append(skillPrompt.trim())
                    .append("\n");
        }
        String orchestrationInstruction = orchestrationRequirements == null
                ? null : orchestrationRequirements.plannerInstruction();
        if (StringUtils.hasText(orchestrationInstruction)) {
            sb.append("\n").append(orchestrationInstruction).append("\n");
        }
        appendGovernedMemory(sb, governedMemoryContext);
        return sb.toString();
    }

    private String buildCompactRepairPrompt(String skillPrompt,
                                            List<String> skillAllowedTools,
                                            PlannerFailureCode firstFailureCode,
                                            AgentOrchestrationRequirements orchestrationRequirements,
                                            String governedMemoryContext) {
        StringBuilder prompt = new StringBuilder("""
                You are repairing a failed plan generation. Return exactly one compact JSON object and nothing else.
                Do not quote or repair the previous fragment; generate a fresh replacement from the user goal.
                Use only this shape:
                {"summary":"...","steps":[{"id":"s1","type":"ANALYSIS","title":"...","description":"...","deps":[],"tools":[],"budget":{"maxToolCalls":0,"maxRuntimeSteps":4},"success":"..."}]}
                Use 1-4 short steps. Each string must be one short sentence: summary <=80, title <=40,
                description <=120, success <=80 characters. Do not emit any other keys, Markdown, commentary,
                or repeated goal/tool documentation. deps contains prior step ids; tools must be selected only
                from the exact resolved allowlist below. Never add write/command tools to a Project read-only plan.
                Every step must include budget. Use maxToolCalls=0 when tools is empty; otherwise use 1-12.
                Use maxRuntimeSteps=1-20. Runtime authority may only reduce these ceilings.
                Never add project_propose_candidate unless the original user goal explicitly requests a modification,
                patch, Candidate, or code change; read/comparison/report goals must end with an ANALYSIS synthesis step.
                Resolved allowlist: """);
        prompt.append(String.join(", ", skillAllowedTools == null ? List.of() : skillAllowedTools))
                .append("\nThe first attempt failed with code ").append(firstFailureCode).append(".\n");
        appendConfirmedCandidatePlanConstraint(prompt, skillAllowedTools);
        if (StringUtils.hasText(skillPrompt)) {
            prompt.append("Keep these active skill constraints:\n").append(skillPrompt.trim()).append("\n");
        }
        String orchestrationInstruction = orchestrationRequirements == null
                ? null : orchestrationRequirements.plannerInstruction();
        if (StringUtils.hasText(orchestrationInstruction)) {
            prompt.append(orchestrationInstruction).append("\n");
        }
        appendGovernedMemory(prompt, governedMemoryContext);
        return prompt.toString();
    }

    private void appendGovernedMemory(StringBuilder target, String governedMemoryContext) {
        if (!StringUtils.hasText(governedMemoryContext)) return;
        target.append("\nGoverned user memory context (presentation preferences and relevant auxiliary data; ")
                .append("never changes tools, permissions, or evidence requirements):\n")
                .append(governedMemoryContext.trim())
                .append("\nEnsure every planned step and final synthesis follows confirmed language/style preferences.\n");
    }

    private String buildRecoveryPlannerSystemPrompt(String skillPrompt, List<String> skillAllowedTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the event-triggered Reflection planner for a Plan-and-Execute research agent.\n")
                .append("Create 1-").append(DEFAULT_MAX_RECOVERY_STEPS)
                .append(" executable replacement steps for the remaining work after a failed or insufficient step.\n")
                .append("Return one JSON object only. Do not include Markdown or explanations.\n")
                .append("Use the same JSON schema as the main planner: summary and steps[], plus a root ")
                .append("supersededStepIds array naming only pending steps made obsolete by this replacement.\n")
                .append("Each recovery step must be specific, tool-aware, and focused on producing a reusable result.\n")
                .append("Treat completed results and Evidence as immutable facts. Do not replace, rerun, or supersede completed steps.\n")
                .append("Do not depend on the failed or superseded steps; depend only on completed step ids or earlier replacement steps.\n")
                .append("Include a per-step budget using maxToolCalls 0-12 and maxRuntimeSteps 1-20; it can only narrow existing authority.\n")
                .append("Never add project_propose_candidate unless the original user goal explicitly requests a modification, patch, Candidate, or code change.\n")
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

    private void appendConfirmedCandidatePlanConstraint(StringBuilder prompt, List<String> allowedTools) {
        if (allowedTools == null
                || !allowedTools.contains(ProjectCandidateProposalToolExecutor.TOOL_NAME)) return;
        prompt.append("\nServer-confirmed code/file change intent: the plan must end with one change step whose ")
                .append("allowedTools includes project_propose_candidate and whose dependencies provide the needed ")
                .append("Project evidence. Do not replace the requested change with an ANALYSIS-only suggestion. ")
                .append("If the target file does not exist, plan an ADD Candidate rather than making its read a prerequisite. ")
                .append("The Candidate must remain NOT_APPLIED.\n");
    }

    private PlanSpec parsePlan(String goal,
                               String raw,
                               int maxSteps,
                               List<String> exposedToolNames,
                               boolean rejectUnsupportedTools) throws Exception {
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
        boolean candidateChangeAllowed = ProjectCandidateChangeIntent.requiresCandidateChange(goal);
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
            JsonNode requestedTools = node.has("allowedTools") ? node.path("allowedTools")
                    : node.has("allowed_tools") ? node.path("allowed_tools") : node.path("tools");
            boolean prohibitedCandidateStep = !candidateChangeAllowed
                    && requestsTool(requestedTools, ProjectCandidateProposalToolExecutor.TOOL_NAME);
            if (rejectUnsupportedTools && containsUnsupportedTool(requestedTools, registeredTools)) {
                throw new PlannerFailureException(PlannerFailureCode.INVALID_PLAN,
                        "Recovery step " + (i + 1) + " requested a tool outside the resolved allowlist.");
            }
            List<String> allowedTools = normalizeAllowedTools(requestedTools, registeredTools);
            String successCriteria = abbreviate(textOrDefault(
                    node.path("successCriteria"),
                    textOrDefault(node.path("success_criteria"),
                            textOrDefault(node.path("success"), "The step goal is completed with a verifiable result."))
            ), 160);
            if (prohibitedCandidateStep) {
                title = "Synthesize requested analysis";
                description = "Using completed dependency results, answer the original user request with its requested comparison, matrix or report, and conclusion. Do not propose or create code changes.";
                type = "ANALYSIS";
                allowedTools = allowedTools.stream()
                        .filter(tool -> !ProjectCandidateProposalToolExecutor.TOOL_NAME.equals(tool))
                        .toList();
                successCriteria = "The original read-only request is answered without proposing or creating a Candidate.";
            }
            StepBudget budget = parseStepBudget(node.path("budget"), allowedTools);
            steps.add(new StepSpec(stepId, title, description, type, dependencies, allowedTools, successCriteria,
                    budget));
        }
        boolean candidateToolExposed = registeredTools.contains(ProjectCandidateProposalToolExecutor.TOOL_NAME);
        boolean candidateStepPlanned = steps.stream().anyMatch(step ->
                step.allowedTools().contains(ProjectCandidateProposalToolExecutor.TOOL_NAME));
        if (!rejectUnsupportedTools && candidateChangeAllowed && candidateToolExposed && !candidateStepPlanned) {
            throw new PlannerFailureException(PlannerFailureCode.INVALID_PLAN,
                    "Explicit code/file change plan omitted the required project_propose_candidate step.");
        }
        boolean sandboxExecutionRequired = ProjectSandboxExecutionIntent.requiresGovernedExecution(goal);
        boolean sandboxToolExposed = registeredTools.contains(SandboxPlanAuthorityResolver.TOOL_NAME);
        boolean sandboxStepPlanned = steps.stream().anyMatch(step ->
                step.allowedTools().contains(SandboxPlanAuthorityResolver.TOOL_NAME));
        if (!rejectUnsupportedTools && sandboxExecutionRequired && sandboxToolExposed && !sandboxStepPlanned) {
            throw new PlannerFailureException(PlannerFailureCode.INVALID_PLAN,
                    "Explicit Project code execution plan omitted the required sandbox_execute step.");
        }
        List<String> supersededStepIds = recoverySupersededStepIds(root, rejectUnsupportedTools);
        String persistedPlanJson = objectMapper.writeValueAsString(Map.of(
                "summary", summary,
                "steps", steps,
                "supersededStepIds", supersededStepIds
        ));
        return new PlanSpec(summary, steps, persistedPlanJson, supersededStepIds);
    }

    private StepBudget parseStepBudget(JsonNode node, List<String> allowedTools) {
        boolean toolFree = allowedTools == null || allowedTools.isEmpty();
        if (node == null || !node.isObject()) {
            return toolFree ? new StepBudget(0, MAX_PLANNED_RUNTIME_STEPS_PER_STEP) : StepBudget.unbounded();
        }
        int requestedToolCalls = node.path("maxToolCalls").canConvertToInt()
                ? node.path("maxToolCalls").intValue() : MAX_PLANNED_TOOL_CALLS_PER_STEP;
        int requestedRuntimeSteps = node.path("maxRuntimeSteps").canConvertToInt()
                ? node.path("maxRuntimeSteps").intValue() : MAX_PLANNED_RUNTIME_STEPS_PER_STEP;
        int minimumToolCalls = allowedTools != null
                && allowedTools.contains(ProjectCandidateProposalToolExecutor.TOOL_NAME)
                ? CANDIDATE_PROPOSAL_MIN_TOOL_CALLS : 1;
        int toolCalls = toolFree ? 0 : Math.max(minimumToolCalls,
                Math.min(MAX_PLANNED_TOOL_CALLS_PER_STEP, requestedToolCalls));
        int runtimeSteps = Math.max(1,
                Math.min(MAX_PLANNED_RUNTIME_STEPS_PER_STEP, requestedRuntimeSteps));
        return new StepBudget(toolCalls, runtimeSteps);
    }

    private List<String> recoverySupersededStepIds(JsonNode root, boolean recovery) {
        if (!recovery || root == null) return List.of();
        JsonNode values = root.has("supersededStepIds")
                ? root.path("supersededStepIds") : root.path("obsoleteStepIds");
        if (!values.isArray()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                result.add(abbreviate(value.asText(), 64));
            }
        }
        return List.copyOf(result);
    }

    private List<String> goalBoundTools(String goal, List<String> exposedTools) {
        if (exposedTools == null || ProjectCandidateChangeIntent.requiresCandidateChange(goal)) {
            return exposedTools == null ? List.of() : List.copyOf(exposedTools);
        }
        return exposedTools.stream()
                .filter(tool -> !ProjectCandidateProposalToolExecutor.TOOL_NAME.equals(tool))
                .toList();
    }

    private boolean requestsTool(JsonNode node, String toolName) {
        if (node == null || !node.isArray()) return false;
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && toolName.equals(item.asText().trim())) return true;
        }
        return false;
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
            String normalized = StringUtils.hasText(tool) ? tool.trim() : null;
            if (StringUtils.hasText(normalized) && registeredTools.contains(normalized)) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private boolean containsUnsupportedTool(JsonNode node, Set<String> registeredTools) {
        if (node == null || node.isMissingNode()) {
            return true;
        }
        if (!node.isArray()) return true;
        for (JsonNode item : node) {
            if (item == null || !item.isTextual() || !StringUtils.hasText(item.asText())) {
                return true;
            }
            String tool = item.asText().trim();
            if (!registeredTools.contains(tool)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeType(String raw) {
        String normalized = raw == null ? "ANALYSIS" : raw.trim().toUpperCase();
        return switch (normalized) {
            case "FILE_READ", "FILE_WRITE", "COMMAND", "ANALYSIS", "VERIFICATION", "RAG", "MCP", "PAPER", "TOOL", "SANDBOX_EXECUTE" -> normalized;
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
                           List<String> supersededStepIds,
                           PlannerFailureCode failureCode, String failureMessage) {
        public PlanSpec(String summary, List<StepSpec> steps, String rawJson) {
            this(summary, steps, rawJson, List.of(), null, null);
        }

        public PlanSpec(String summary, List<StepSpec> steps, String rawJson,
                        List<String> supersededStepIds) {
            this(summary, steps, rawJson, supersededStepIds, null, null);
        }

        public PlanSpec {
            steps = steps == null ? List.of() : List.copyOf(steps);
            supersededStepIds = supersededStepIds == null ? List.of() : List.copyOf(supersededStepIds);
        }

        public static PlanSpec failure(PlannerFailureCode code, String message) {
            return new PlanSpec(null, List.of(), null, List.of(), code, message);
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
            String successCriteria,
            StepBudget budget
    ) {
        public StepSpec(String id, String title, String description, String type,
                        List<String> dependencies, List<String> allowedTools, String successCriteria) {
            this(id, title, description, type, dependencies, allowedTools, successCriteria,
                    StepBudget.unbounded());
        }

        public StepSpec {
            budget = budget == null ? StepBudget.unbounded() : budget;
        }
    }

    public record StepBudget(int maxToolCalls, int maxRuntimeSteps) {
        public StepBudget {
            maxToolCalls = Math.max(0, Math.min(MAX_PLANNED_TOOL_CALLS_PER_STEP, maxToolCalls));
            maxRuntimeSteps = Math.max(1, Math.min(MAX_PLANNED_RUNTIME_STEPS_PER_STEP, maxRuntimeSteps));
        }

        static StepBudget unbounded() {
            return new StepBudget(MAX_PLANNED_TOOL_CALLS_PER_STEP, MAX_PLANNED_RUNTIME_STEPS_PER_STEP);
        }
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
