package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanReflectionRuntimeAdapterTest {

    @Test
    void reflectionSummaryExposesDegradedAndFailedLimitations() {
        PlanAgentService planAgentService = mock(PlanAgentService.class);
        PlanReflectionRuntimeAdapter adapter = new PlanReflectionRuntimeAdapter(planAgentService, new AgentStrategySelector());
        AgentPlanResponse response = new AgentPlanResponse(
                81L,
                21L,
                "Assess roadmap risks",
                "Assess roadmap risks",
                "FAILED",
                true,
                null,
                "plan stalled on missing evidence",
                null,
                null,
                null,
                null,
                List.of(
                        new AgentPlanStepResponse(1L, "step_1", 1, "Collect evidence", "Collect evidence", "ANALYSIS",
                                List.of(), List.of(), "Evidence is collected", "DEGRADED", 2,
                                "partial evidence only", "missing primary source", null, null),
                        new AgentPlanStepResponse(2L, "step_2", 2, "Write summary", "Write summary", "SYNTHESIS",
                                List.of("step_1"), List.of(), "Summary is complete", "FAILED", 2,
                                null, "dependency evidence still incomplete", null, null)
                )
        );
        when(planAgentService.createAndExecuteRuntimeReflectionPlan(11L, 21L, "Assess roadmap risks", true, null))
                .thenReturn(response);

        AgentRuntimeResult result = adapter.run(new AgentRuntimeRequest(
                AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION,
                21L,
                List.of(),
                11L,
                "/plan reflect Assess roadmap risks",
                "deepseek",
                "deepseek-chat",
                null,
                null,
                8,
                true,
                null,
                null,
                null,
                null,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                List.of(),
                0,
                1,
                "trace-reflect",
                null,
                null
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("FAILURE");
        assertThat(result.assistantContent()).contains("Plan status: FAILED");
        assertThat(result.assistantContent()).contains("step_1 [DEGRADED]");
        assertThat(result.assistantContent()).contains("step_2 [FAILED]");
        assertThat(result.assistantContent()).contains("plan error: plan stalled on missing evidence");
        assertThat(result.assistantContent()).contains("Follow-up suggestions:");
    }
}
