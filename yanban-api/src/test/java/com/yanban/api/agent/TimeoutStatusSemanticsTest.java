package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.AgentTaskOutcome;
import com.yanban.core.agent.AgentTaskStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimeoutStatusSemanticsTest {

    @Test
    void timeoutUsesFailedLifecycleAndTimedOutOutcomeAcrossRunPlanAndEvent() {
        AgentRuntimeResult runtime = new AgentRuntimeResult(false, null, List.of(), 1,
                "Sandbox execution timed out.", List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.TIMED_OUT,
                        "TIMED_OUT", false, null)
                .withPlanId(19L)
                .withPlanPersistenceLevel("L2_DURABLE");

        AgentRunProjection projection = AgentRunProjection.fromRuntime(runtime,
                new AgentRunIdentity("AGENT_PLAN", "19", 7L, 11L, 42L));

        assertThat(projection.state().status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(projection.state().outcome()).isEqualTo(AgentTaskOutcome.TIMED_OUT);

        AgentPlanStepResponse timedOutStep = new AgentPlanStepResponse(
                1L, "step_1", 1, "Run", "Run in sandbox", "SANDBOX_EXECUTE",
                List.of(), List.of("sandbox_execute"), "Return receipt", "FAILED", 1,
                "stdout retained", "SANDBOX_TIMED_OUT", null, null);
        AgentPlanResponse plan = new AgentPlanResponse(
                19L, 11L, "Run", "Run in sandbox", "FAILED", true,
                null, "Step step_1 failed: SANDBOX_TIMED_OUT", null, null, null, null,
                List.of(timedOutStep));

        assertThat(plan.status()).isEqualTo("FAILED");
        assertThat(plan.executionOutcome()).isEqualTo("TIMED_OUT");

        Map<String, Object> event = PlanAgentService.planBudgetExceededEventPayload(
                "trace-19", "TIMED_OUT: Plan execution budget exceeded.");
        assertThat(event).containsEntry("status", "FAILED").containsEntry("outcome", "TIMED_OUT");
    }
}
