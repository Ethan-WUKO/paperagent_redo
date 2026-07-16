package com.yanban.api.agent;

import com.yanban.core.research.ResearchToolContracts;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Deterministic selector over server-resolved capability, scope, tools and budgets. */
@Component
public class AgentStrategySelector {

    private static final int AUTO_PLAN_MIN_STEPS = 2;
    private static final int AUTO_PLAN_MIN_TOOL_CALLS = 2;
    private static final List<AgentStrategy> CHAT_CANDIDATES = List.of(
            AgentStrategy.DIRECT, AgentStrategy.SINGLE_STEP_REACT);
    private static final List<AgentStrategy> PROJECT_CANDIDATES = List.of(
            AgentStrategy.DIRECT, AgentStrategy.SINGLE_STEP_REACT, AgentStrategy.PLAN_EXECUTE);
    private static final Map<ResearchMaterialKind, List<String>> MATERIAL_TOOLS = materialTools();

    public AgentStrategy select(String userMessage, AgentToolPolicyEngine.Decision toolPolicy) {
        return select(userMessage, toolPolicy == null ? null : toolPolicy.resolved(), isPlanReflectIntent(userMessage));
    }

    /** Compatibility entry point; only the exact legacy command can request reflection. */
    public AgentStrategy select(String userMessage, ResolvedToolPolicy toolPolicy, boolean explicitPlanRequest) {
        if (explicitPlanRequest && isPlanReflectIntent(userMessage)) {
            return AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION;
        }
        AgentRuntimeRequest synthetic = syntheticRequest(userMessage, toolPolicy);
        return decide(new AgentCoordinationRequest(synthetic, AgentRequestCapability.CHAT, null, null)).selectedStrategy();
    }

    public AgentStrategy select(AgentCoordinationRequest request) {
        return decide(request).selectedStrategy();
    }

    public AgentStrategySelection decide(AgentCoordinationRequest request) {
        AgentRuntimeRequest runtime = request.runtimeRequest();
        AgentStrategy requested = runtime.strategy() == null ? AgentStrategy.AUTO : runtime.strategy();
        List<AgentStrategy> candidates = candidates(request.capability());
        Analysis analysis = analyze(runtime.userMessage(), request.capability(), runtime.toolPolicy(), runtime.maxSteps());

        if (request.capability() == AgentRequestCapability.LEGACY_PLAN_REFLECT) {
            return selection(requested, AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION, requested != AgentStrategy.AUTO,
                    false, null, candidates, analysis, List.of(AgentStrategyReasonCode.LEGACY_REFLECTION_CAPABILITY),
                    "explicit_legacy_plan_reflect");
        }
        if (request.capability() == AgentRequestCapability.TRUSTED_PLAN_API
                || request.capability() == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ) {
            String reason = request.capability() == AgentRequestCapability.TRUSTED_PLAN_API
                    ? "trusted_plan_api" : "trusted_project_plan_read";
            return selection(requested, AgentStrategy.PLAN_EXECUTE, requested != AgentStrategy.AUTO,
                    false, null, candidates, analysis, List.of(AgentStrategyReasonCode.TRUSTED_PLAN_CAPABILITY), reason);
        }

        if (requested != AgentStrategy.AUTO) {
            AgentStrategySelection explicit = explicitSelection(requested, candidates, analysis);
            if (explicit != null) {
                return explicit;
            }
        }
        return autoSelection(requested, candidates, analysis, requested != AgentStrategy.AUTO);
    }

    private AgentStrategySelection explicitSelection(AgentStrategy requested,
                                                      List<AgentStrategy> candidates,
                                                      Analysis analysis) {
        if (!candidates.contains(requested)) {
            return null;
        }
        if (requested == AgentStrategy.DIRECT) {
            return selection(requested, AgentStrategy.DIRECT, true, false, null, candidates, analysis,
                    List.of(AgentStrategyReasonCode.EXPLICIT_STRATEGY_SELECTED), "explicit_strategy_direct");
        }
        if (requested == AgentStrategy.SINGLE_STEP_REACT && analysis.reactExecutable()) {
            return selection(requested, requested, true, false, null, candidates, analysis,
                    List.of(AgentStrategyReasonCode.EXPLICIT_STRATEGY_SELECTED), "explicit_strategy_react");
        }
        if (requested == AgentStrategy.PLAN_EXECUTE && analysis.planExecutable()) {
            return selection(requested, requested, true, false, null, candidates, analysis,
                    List.of(AgentStrategyReasonCode.EXPLICIT_STRATEGY_SELECTED), "explicit_strategy_plan");
        }
        return null;
    }

    private AgentStrategySelection autoSelection(AgentStrategy requested,
                                                 List<AgentStrategy> candidates,
                                                 Analysis analysis,
                                                 boolean rejectedExplicit) {
        List<AgentStrategyReasonCode> reasons = new ArrayList<>();
        if (rejectedExplicit) {
            reasons.add(AgentStrategyReasonCode.EXPLICIT_STRATEGY_NOT_ALLOWED);
        }
        boolean wantedPlan = candidates.contains(AgentStrategy.PLAN_EXECUTE) && analysis.planTask();
        if (wantedPlan && analysis.planExecutable()) {
            reasons.add(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN);
            return selection(requested, AgentStrategy.PLAN_EXECUTE, false, false, null, candidates, analysis,
                    reasons, "auto_cross_material_plan");
        }
        if (wantedPlan) {
            if (!analysis.planBudgetAvailable()) {
                reasons.add(AgentStrategyReasonCode.PLAN_BUDGET_INSUFFICIENT);
            }
            if (!analysis.materialCoverageComplete()) {
                reasons.add(AgentStrategyReasonCode.MATERIAL_COVERAGE_INCOMPLETE);
            }
        }
        if (analysis.toolTask() && analysis.reactExecutable()) {
            reasons.add(wantedPlan ? AgentStrategyReasonCode.DEGRADED_TO_REACT
                    : AgentStrategyReasonCode.AUTO_TOOL_TASK_REACT);
            return selection(requested, AgentStrategy.SINGLE_STEP_REACT, false, wantedPlan || rejectedExplicit,
                    wantedPlan ? AgentStrategy.PLAN_EXECUTE : rejectedExplicit ? requested : null,
                    candidates, analysis, reasons, wantedPlan ? "auto_plan_degraded_to_react" : "auto_tool_task_react");
        }
        boolean wantedReact = analysis.toolTask();
        if (!analysis.toolsAvailable()) {
            reasons.add(AgentStrategyReasonCode.NO_ALLOWED_TOOLS);
        } else if (!analysis.toolBudgetAvailable()) {
            reasons.add(AgentStrategyReasonCode.TOOL_BUDGET_INSUFFICIENT);
        }
        if (wantedPlan || rejectedExplicit || wantedReact) {
            reasons.add(AgentStrategyReasonCode.DEGRADED_TO_DIRECT);
        } else {
            reasons.add(AgentStrategyReasonCode.AUTO_SIMPLE_DIRECT);
        }
        AgentStrategy degradedFrom = wantedPlan ? AgentStrategy.PLAN_EXECUTE
                : rejectedExplicit ? requested : wantedReact ? AgentStrategy.SINGLE_STEP_REACT : null;
        String reason = wantedPlan ? "auto_plan_degraded_to_direct"
                : wantedReact ? "auto_react_degraded_to_direct" : "auto_simple_direct";
        return selection(requested, AgentStrategy.DIRECT, false, degradedFrom != null, degradedFrom,
                candidates, analysis, reasons, reason);
    }

    private AgentStrategySelection selection(AgentStrategy requested,
                                             AgentStrategy selected,
                                             boolean explicit,
                                             boolean degraded,
                                             AgentStrategy degradedFrom,
                                             List<AgentStrategy> candidates,
                                             Analysis analysis,
                                             List<AgentStrategyReasonCode> additionalReasons,
                                             String reason) {
        LinkedHashSet<AgentStrategyReasonCode> reasonCodes = new LinkedHashSet<>(analysis.reasonCodes());
        reasonCodes.addAll(additionalReasons);
        AgentStrategySelectionOrigin origin = selectionOrigin(requested, explicit, reasonCodes);
        AgentOrchestrationRequirements orchestration = new AgentOrchestrationRequirements(
                analysis.signals(), List.copyOf(reasonCodes), analysis.materialRequirements(), origin);
        return new AgentStrategySelection(requested, selected, explicit, degraded, degradedFrom,
                candidates, orchestration, reason);
    }

    private Analysis analyze(String userMessage,
                             AgentRequestCapability capability,
                             ResolvedToolPolicy policy,
                             int maxSteps) {
        String normalized = normalize(userMessage);
        Set<String> allowedTools = policy == null ? Set.of() : new LinkedHashSet<>(policy.allowedTools());
        boolean project = capability == AgentRequestCapability.PROJECT_READ
                || capability == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ;
        boolean toolsAvailable = !allowedTools.isEmpty();
        boolean toolBudgetAvailable = toolsAvailable && policy.maxToolCalls() > 0;

        List<ResearchMaterialKind> materials = new ArrayList<>();
        if (containsAny(normalized, "paper", "papers", "manuscript", "latex", "tex", "equation", "论文", "文章", "手稿", "章节", "公式")) {
            materials.add(ResearchMaterialKind.PAPER_LATEX);
        }
        if (containsAny(normalized, "code", "source code", "implementation", "repository", "script", "java", "python",
                "代码", "源码", "实现", "仓库", "脚本")) {
            materials.add(ResearchMaterialKind.CODE);
        }
        if (containsAny(normalized, "experiment", "experiments", "result", "results", "metric", "metrics", "configuration", "config", "csv", "yaml", "json",
                "实验", "结果", "指标", "配置", "日志", "数据")) {
            materials.add(ResearchMaterialKind.EXPERIMENT_CONFIG);
        }
        if (containsAny(normalized, "bibtex", "bib", "citation", "citations", "bibliography", "reference", "references", "引用", "参考文献", "文献")) {
            materials.add(ResearchMaterialKind.BIBTEX);
        }
        boolean verification = containsAny(normalized, "verify", "validate", "audit", "check consistency", "reproduce",
                "cross-check", "核验", "核对", "验证", "审计", "一致性", "复现", "对照");
        boolean multiStage = containsAny(normalized, "compare", "synthesize", "then", "multi-stage", "across", "correlate",
                "比较", "对比", "结合", "综合", "然后", "多阶段", "跨材料", "关联", "先", "再");
        boolean toolUseRequested = containsAny(normalized, "search", "find", "look up", "look it up", "inspect", "read", "summarize",
                "analyze", "audit", "查找", "搜索", "检索", "读取", "检查", "分析", "总结", "审计");
        boolean simpleQuestion = containsAny(normalized, "what is", "why", "how does", "explain", "是什么", "为什么", "解释")
                && !toolUseRequested && materials.isEmpty();
        boolean crossMaterial = materials.size() >= 2;

        List<ResearchMaterialRequirement> requirements = new ArrayList<>();
        for (ResearchMaterialKind material : materials) {
            List<String> accepted = MATERIAL_TOOLS.get(material);
            List<String> available = accepted.stream().filter(allowedTools::contains).toList();
            requirements.add(new ResearchMaterialRequirement(material, accepted, available, !available.isEmpty()));
        }
        boolean materialCoverageComplete = requirements.stream().allMatch(ResearchMaterialRequirement::covered);
        boolean planBudgetAvailable = maxSteps >= AUTO_PLAN_MIN_STEPS
                && policy != null && policy.maxToolCalls() >= Math.max(AUTO_PLAN_MIN_TOOL_CALLS, requirements.size());
        boolean planTask = project && crossMaterial && (multiStage || verification);
        boolean toolTask = project || toolUseRequested;

        LinkedHashSet<AgentStrategySignal> signals = new LinkedHashSet<>();
        if (project) {
            signals.add(AgentStrategySignal.PROJECT_SCOPE);
            signals.add(AgentStrategySignal.PROJECT_READ_REQUIRED);
        }
        signals.add(toolsAvailable ? AgentStrategySignal.TOOLS_AVAILABLE : AgentStrategySignal.TOOLS_DENIED);
        if (toolUseRequested) signals.add(AgentStrategySignal.TOOL_USE_REQUESTED);
        if (simpleQuestion) signals.add(AgentStrategySignal.SIMPLE_QUESTION);
        if (multiStage) signals.add(AgentStrategySignal.MULTI_STAGE_TASK);
        if (verification) signals.add(AgentStrategySignal.VERIFICATION_REQUIRED);
        for (ResearchMaterialKind material : materials) signals.add(materialSignal(material));
        if (crossMaterial) signals.add(AgentStrategySignal.CROSS_MATERIAL_TASK);
        if (planTask) signals.add(planBudgetAvailable ? AgentStrategySignal.PLAN_BUDGET_AVAILABLE
                : AgentStrategySignal.PLAN_BUDGET_INSUFFICIENT);
        if (isPlanReflectIntent(userMessage)) signals.add(AgentStrategySignal.REFLECTION_COMMAND);

        return new Analysis(List.copyOf(signals), List.of(), List.copyOf(requirements), toolsAvailable,
                toolBudgetAvailable, planBudgetAvailable, materialCoverageComplete, planTask, toolTask);
    }

    private AgentStrategySignal materialSignal(ResearchMaterialKind material) {
        return switch (material) {
            case PAPER_LATEX -> AgentStrategySignal.MATERIAL_PAPER_LATEX;
            case CODE -> AgentStrategySignal.MATERIAL_CODE;
            case EXPERIMENT_CONFIG -> AgentStrategySignal.MATERIAL_EXPERIMENT_CONFIG;
            case BIBTEX -> AgentStrategySignal.MATERIAL_BIBTEX;
        };
    }

    private AgentStrategySelectionOrigin selectionOrigin(AgentStrategy requested,
                                                         boolean explicit,
                                                         Set<AgentStrategyReasonCode> reasons) {
        if (reasons.contains(AgentStrategyReasonCode.TRUSTED_PLAN_CAPABILITY)
                || reasons.contains(AgentStrategyReasonCode.LEGACY_REFLECTION_CAPABILITY)) {
            return AgentStrategySelectionOrigin.TRUSTED_CAPABILITY;
        }
        if (explicit) return AgentStrategySelectionOrigin.EXPLICIT_OVERRIDE;
        if (requested != AgentStrategy.AUTO) return AgentStrategySelectionOrigin.EXPLICIT_FALLBACK;
        return AgentStrategySelectionOrigin.SERVER_AUTO;
    }

    private List<AgentStrategy> candidates(AgentRequestCapability capability) {
        return switch (capability) {
            case CHAT -> CHAT_CANDIDATES;
            case PROJECT_READ -> PROJECT_CANDIDATES;
            case TRUSTED_PLAN_API, TRUSTED_PROJECT_PLAN_READ -> List.of(AgentStrategy.PLAN_EXECUTE);
            case LEGACY_PLAN_REFLECT -> List.of(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION);
        };
    }

    private static Map<ResearchMaterialKind, List<String>> materialTools() {
        EnumMap<ResearchMaterialKind, List<String>> tools = new EnumMap<>(ResearchMaterialKind.class);
        tools.put(ResearchMaterialKind.PAPER_LATEX,
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE, "project_read_file"));
        tools.put(ResearchMaterialKind.CODE,
                List.of(ResearchToolContracts.PROJECT_CODE_SYMBOLS, "project_read_file"));
        tools.put(ResearchMaterialKind.EXPERIMENT_CONFIG,
                List.of(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY, "project_read_file"));
        tools.put(ResearchMaterialKind.BIBTEX,
                List.of(ResearchToolContracts.PROJECT_BIBTEX_AUDIT, "project_read_file"));
        return Map.copyOf(tools);
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) return "";
        return " " + value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim() + " ";
    }

    private boolean containsAny(String normalized, String... phrases) {
        for (String phrase : phrases) {
            String candidate = phrase.matches(".*[\\p{IsHan}].*") ? phrase : " " + phrase + " ";
            if (normalized.contains(candidate)) return true;
        }
        return false;
    }

    public boolean isPlanReflectIntent(String userMessage) {
        if (!StringUtils.hasText(userMessage)) return false;
        String normalized = userMessage.trim();
        String command = "/plan reflect";
        return normalized.equalsIgnoreCase(command)
                || (normalized.length() > command.length()
                && normalized.regionMatches(true, 0, command, 0, command.length())
                && Character.isWhitespace(normalized.charAt(command.length())));
    }

    private AgentRuntimeRequest syntheticRequest(String userMessage, ResolvedToolPolicy toolPolicy) {
        ResolvedToolPolicy resolved = toolPolicy == null ? ResolvedToolPolicy.denyAll(0, 0, "selector_compat") : toolPolicy;
        return new AgentRuntimeRequest(AgentStrategy.AUTO, null, List.of(), 1L,
                StringUtils.hasText(userMessage) ? userMessage : "empty", "selector", "selector", null, null,
                2, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, resolved, resolved.maxToolCalls(),
                resolved.maxDuplicateToolCalls(), "selector", null, null);
    }

    private record Analysis(
            List<AgentStrategySignal> signals,
            List<AgentStrategyReasonCode> reasonCodes,
            List<ResearchMaterialRequirement> materialRequirements,
            boolean toolsAvailable,
            boolean toolBudgetAvailable,
            boolean planBudgetAvailable,
            boolean materialCoverageComplete,
            boolean planTask,
            boolean toolTask
    ) {
        boolean reactExecutable() {
            return toolsAvailable && toolBudgetAvailable;
        }

        boolean planExecutable() {
            return planBudgetAvailable && materialCoverageComplete;
        }
    }
}
