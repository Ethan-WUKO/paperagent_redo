package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentStrategySelectorTest {

    private final AgentStrategySelector selector = new AgentStrategySelector();

    @Test
    void explicitPlanReflectUsesReflectionStrategy() {
        AgentToolPolicyEngine.Decision toolPolicy = new AgentToolPolicyEngine.Decision(List.of("search_web"), 1, 1, "search");

        assertThat(selector.select("/plan reflect audit the current roadmap gaps", toolPolicy))
                .isEqualTo(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION);
    }

    @Test
    void emptyToolPolicyFallsBackToDirect() {
        AgentToolPolicyEngine.Decision toolPolicy = new AgentToolPolicyEngine.Decision(List.of(), 0, 1, "direct");

        assertThat(selector.select("Explain the current architecture.", toolPolicy))
                .isEqualTo(AgentStrategy.DIRECT);
    }

    @Test
    void toolEnabledFallbackUsesSingleStepReact() {
        AgentToolPolicyEngine.Decision toolPolicy = new AgentToolPolicyEngine.Decision(List.of("search_web"), 1, 1, "search");

        assertThat(selector.select("Look up the latest DeepSeek release.", toolPolicy))
                .isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
    }
}
