package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.research.ResearchToolContracts;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentStrategySelectorTest {

    private static final List<String> RESEARCH_TOOLS = List.of(
            "project_manifest",
            "project_read_file",
            ResearchToolContracts.PROJECT_LATEX_OUTLINE,
            ResearchToolContracts.PROJECT_CODE_SYMBOLS,
            ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY,
            ResearchToolContracts.PROJECT_BIBTEX_AUDIT,
            ResearchToolContracts.PROJECT_CROSS_MATERIAL_SEARCH);

    private final AgentStrategySelector selector = new AgentStrategySelector();

    @Test
    void explicitPlanReflectUsesOnlyRestrictedReflectionCapability() {
        AgentToolPolicyEngine.Decision toolPolicy = new AgentToolPolicyEngine.Decision(
                List.of("search_web"), 1, 1, "search");

        assertThat(selector.select("/plan reflect audit the current roadmap gaps", toolPolicy))
                .isEqualTo(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION);
        assertThat(selector.decide(AgentCoordinationRequest.chat(
                request(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION, "please reflect", List.of("search_web"), 2, 2))))
                .satisfies(decision -> {
                    assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
                    assertThat(decision.orchestration().reasonCodes())
                            .contains(AgentStrategyReasonCode.EXPLICIT_STRATEGY_NOT_ALLOWED);
                });
    }

    @Test
    void explicitServerAllowedStrategyOverridesAutoSignals() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.DIRECT,
                "Compare the LaTeX paper with code and experiment results, then verify BibTeX citations.",
                RESEARCH_TOOLS, 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
        assertThat(decision.explicitOverride()).isTrue();
        assertThat(decision.orchestration().selectionOrigin())
                .isEqualTo(AgentStrategySelectionOrigin.EXPLICIT_OVERRIDE);
        assertThat(decision.degraded()).isFalse();
        assertThat(decision.orchestration().reasonCodes())
                .contains(AgentStrategyReasonCode.EXPLICIT_STRATEGY_SELECTED);
    }

    @Test
    void simpleQuestionStaysDirectEvenWhenToolsAreAvailable() {
        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.chat(
                request(AgentStrategy.AUTO, "What is retrieval augmented generation?", List.of("search_web"), 4, 4)));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
        assertThat(decision.orchestration().signals()).contains(AgentStrategySignal.SIMPLE_QUESTION);
    }

    @Test
    void explicitLookupUsesReactAndNaturalLanguageCannotPromoteChatToPlan() {
        AgentStrategySelection lookup = selector.decide(AgentCoordinationRequest.chat(
                request(null, "Look up the latest release.", List.of("search_web"), 2, 2)));
        AgentStrategySelection planWords = selector.decide(AgentCoordinationRequest.chat(
                request(null, "Analyze this and propose a multi-stage plan.", List.of("search_web"), 6, 6)));

        assertThat(lookup.selectedStrategy()).isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
        assertThat(planWords.selectedStrategy()).isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
        assertThat(planWords.serverCandidates()).doesNotContain(AgentStrategy.PLAN_EXECUTE);
        assertThat(selector.isPlanReflectIntent("/plan reflection should not match")).isFalse();
    }

    @Test
    void projectCrossMaterialTaskSelectsPlanWithAuditableCoverage() throws Exception {
        String task = "Compare the LaTeX paper and source code with experiment config/results, "
                + "then verify every BibTeX citation.";
        AgentCoordinationRequest request = AgentCoordinationRequest.projectRead(
                projectRequest(AgentStrategy.AUTO, task, RESEARCH_TOOLS, 8, 12));

        AgentStrategySelection first = selector.decide(request);
        AgentStrategySelection second = selector.decide(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(first.orchestration().selectionOrigin()).isEqualTo(AgentStrategySelectionOrigin.SERVER_AUTO);
        assertThat(first.orchestration().signals()).contains(
                AgentStrategySignal.PROJECT_SCOPE,
                AgentStrategySignal.MATERIAL_PAPER_LATEX,
                AgentStrategySignal.MATERIAL_CODE,
                AgentStrategySignal.MATERIAL_EXPERIMENT_CONFIG,
                AgentStrategySignal.MATERIAL_BIBTEX,
                AgentStrategySignal.CROSS_MATERIAL_TASK,
                AgentStrategySignal.VERIFICATION_REQUIRED);
        assertThat(first.orchestration().materialRequirements())
                .extracting(ResearchMaterialRequirement::material)
                .containsExactly(ResearchMaterialKind.PAPER_LATEX, ResearchMaterialKind.CODE,
                        ResearchMaterialKind.EXPERIMENT_CONFIG, ResearchMaterialKind.BIBTEX);
        assertThat(first.orchestration().materialRequirements()).allMatch(ResearchMaterialRequirement::covered);
        assertThat(new ObjectMapper().writeValueAsString(first))
                .contains("AUTO", "SERVER_AUTO", "AUTO_CROSS_MATERIAL_PLAN", "PAPER_LATEX")
                .doesNotContain(task);
    }

    @Test
    void insufficientPlanBudgetDegradesToReactWithoutChangingTools() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.AUTO,
                "Compare the LaTeX paper with code and verify experiment results.", RESEARCH_TOOLS, 1, 1);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.degradedFrom()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.orchestration().reasonCodes()).contains(
                AgentStrategyReasonCode.PLAN_BUDGET_INSUFFICIENT,
                AgentStrategyReasonCode.DEGRADED_TO_REACT);
        assertThat(request.allowedToolNames()).containsExactlyElementsOf(RESEARCH_TOOLS);
    }

    @Test
    void recognizesChineseCrossMaterialResearchSignalsDeterministically() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.AUTO,
                "对比论文、代码与实验配置，并核对参考文献引用的一致性。", RESEARCH_TOOLS, 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.orchestration().materialRequirements())
                .extracting(ResearchMaterialRequirement::material)
                .containsExactly(ResearchMaterialKind.PAPER_LATEX, ResearchMaterialKind.CODE,
                        ResearchMaterialKind.EXPERIMENT_CONFIG, ResearchMaterialKind.BIBTEX);
        assertThat(decision.orchestration().signals()).contains(
                AgentStrategySignal.MULTI_STAGE_TASK, AgentStrategySignal.VERIFICATION_REQUIRED,
                AgentStrategySignal.CROSS_MATERIAL_TASK);
    }

    @Test
    void incompleteToolPolicyCoverageDegradesAndNeverSynthesizesMaterialTools() {
        List<String> intersectedPolicy = List.of("project_manifest", ResearchToolContracts.PROJECT_LATEX_OUTLINE);
        AgentRuntimeRequest request = projectRequest(AgentStrategy.AUTO,
                "Compare the LaTeX paper with code and verify experiment results.", intersectedPolicy, 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
        assertThat(decision.orchestration().reasonCodes())
                .contains(AgentStrategyReasonCode.MATERIAL_COVERAGE_INCOMPLETE);
        assertThat(decision.orchestration().materialRequirements())
                .filteredOn(requirement -> requirement.material() == ResearchMaterialKind.CODE
                        || requirement.material() == ResearchMaterialKind.EXPERIMENT_CONFIG)
                .allMatch(requirement -> !requirement.covered() && requirement.availableTools().isEmpty());
        assertThat(request.allowedToolNames()).containsExactlyElementsOf(intersectedPolicy);
    }

    @Test
    void deniedToolPolicyFailsClosedToDirect() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT,
                "Read and inspect the project code.", List.of(), 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.degradedFrom()).isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
        assertThat(decision.orchestration().reasonCodes()).contains(
                AgentStrategyReasonCode.EXPLICIT_STRATEGY_NOT_ALLOWED,
                AgentStrategyReasonCode.NO_ALLOWED_TOOLS,
                AgentStrategyReasonCode.DEGRADED_TO_DIRECT);
    }

    @Test
    void projectAutoDefaultsToReadOnlyReactWithoutMaterialKeywords() {
        List<String> explorationTools = List.of("project_manifest", "project_read_file");
        AgentStrategySelection english = selector.decide(AgentCoordinationRequest.projectRead(projectRequest(
                AgentStrategy.AUTO, "List this project's directory structure.", explorationTools, 4, 4)));
        AgentStrategySelection chinese = selector.decide(AgentCoordinationRequest.projectRead(projectRequest(
                AgentStrategy.AUTO, "README 里说明了什么？", explorationTools, 4, 4)));

        assertThat(List.of(english.selectedStrategy(), chinese.selectedStrategy()))
                .containsOnly(AgentStrategy.SINGLE_STEP_REACT);
        assertThat(List.of(english, chinese)).allSatisfy(decision -> {
            assertThat(decision.orchestration().signals()).contains(
                    AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.PROJECT_READ_REQUIRED);
            assertThat(decision.orchestration().reasonCodes())
                    .contains(AgentStrategyReasonCode.AUTO_TOOL_TASK_REACT);
            assertThat(decision.orchestration().selectionOrigin())
                    .isEqualTo(AgentStrategySelectionOrigin.SERVER_AUTO);
        });
    }

    @Test
    void projectExplorationWithoutToolBudgetDegradesFromReactToDirect() {
        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(projectRequest(
                AgentStrategy.AUTO, "README 里说明了什么？", List.of("project_read_file"), 4, 0)));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.degradedFrom()).isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
        assertThat(decision.orchestration().reasonCodes()).contains(
                AgentStrategyReasonCode.TOOL_BUDGET_INSUFFICIENT,
                AgentStrategyReasonCode.DEGRADED_TO_DIRECT);
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy,
                                               String message,
                                               List<String> tools,
                                               int maxSteps,
                                               int maxToolCalls) {
        return request(strategy, message, tools, maxSteps, maxToolCalls)
                .withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private AgentRuntimeRequest request(AgentStrategy strategy,
                                        String message,
                                        List<String> tools,
                                        int maxSteps,
                                        int maxToolCalls) {
        return new AgentRuntimeRequest(strategy, 11L, List.of(ChatMessage.user("prior")), 7L, message,
                "test", "model", null, null, maxSteps, true, null, "key", "url", null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(tools, maxToolCalls, Math.min(1, maxToolCalls), "intersected_test_policy"),
                maxToolCalls, Math.min(1, maxToolCalls), "trace", null, null);
    }
}
