package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.model.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeCoordinatorTest {

    @Test
    void coordinatesDirectReactAndExplicitLegacyPlanDeterministically() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        when(runtime.run(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            AgentRuntimeRequest request = invocation.getArgument(0);
            return new AgentRuntimeResult(true, "ok", List.of(ChatMessage.assistant("ok")), 1,
                    null, List.of(), List.of(), null, null, null);
        });
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());

        AgentCoordinationResult direct = coordinator.coordinate(AgentCoordinationRequest.chat(request("hello", List.of())));
        AgentCoordinationResult react = coordinator.coordinate(AgentCoordinationRequest.chat(request("look it up", List.of("search_web"))));
        AgentCoordinationResult legacy = coordinator.coordinate(new AgentCoordinationRequest(
                request("/plan reflect audit", List.of("search_web")), true));

        assertThat(direct.runtimeResult().selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
        assertThat(react.runtimeResult().selectedStrategy()).isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
        assertThat(legacy.runtimeResult().selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION);
        assertThat(legacy.runtimeResult().stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(legacy.runtimeResult().outcome()).isEqualTo("SUCCESS");
        assertThat(legacy.runtimeResult().degraded()).isFalse();
    }

    @Test
    void trustedPlanApiSelectsPlanExecuteWithoutNaturalLanguagePromotion() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        when(runtime.run(org.mockito.ArgumentMatchers.any())).thenReturn(new AgentRuntimeResult(
                true, "ok", List.of(ChatMessage.assistant("ok")), 1, null, List.of(), List.of(), null, null, null));
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());

        AgentCoordinationResult result = coordinator.coordinate(AgentCoordinationRequest.trustedPlanApi(
                request("ordinary persisted plan goal", List.of()), 91L));

        assertThat(result.decision().selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(result.runtimeResult().selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        org.mockito.ArgumentCaptor<AgentRuntimeRequest> captured = org.mockito.ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(runtime).run(captured.capture());
        assertThat(captured.getValue().planId()).isEqualTo(91L);
    }

    @Test
    void rejectsMissingAndConflictingTrustedPlanIdsBeforeAdapterExecution() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());

        AgentCoordinationResult missing = coordinator.coordinate(AgentCoordinationRequest.trustedPlanApi(
                request("persisted plan", List.of()), null));
        AgentRuntimeRequest conflictingRuntime = request("persisted plan", List.of()).withPlanId(12L);
        AgentCoordinationResult conflicting = coordinator.coordinate(AgentCoordinationRequest.trustedPlanApi(
                conflictingRuntime, 13L));

        assertThat(missing.runtimeResult().stopReason()).isEqualTo(AgentStopReason.POLICY_REJECTED);
        assertThat(conflicting.runtimeResult().stopReason()).isEqualTo(AgentStopReason.POLICY_REJECTED);
        verify(runtime, org.mockito.Mockito.never()).run(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptsMatchingLegacyRuntimePlanIdButBindsTheCoordinatorIdentity() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        when(runtime.run(org.mockito.ArgumentMatchers.any())).thenReturn(new AgentRuntimeResult(
                true, "ok", List.of(), 0, null, List.of(), List.of(), null, null, null));
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());

        AgentCoordinationResult result = coordinator.coordinate(AgentCoordinationRequest.trustedPlanApi(
                request("persisted plan", List.of()).withPlanId(91L), 91L));

        assertThat(result.runtimeResult().success()).isTrue();
        org.mockito.ArgumentCaptor<AgentRuntimeRequest> captured = org.mockito.ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(runtime).run(captured.capture());
        assertThat(captured.getValue().planId()).isEqualTo(91L);
    }

    @Test
    void rejectsLegacyCapabilityUnlessTheExactCommandWasUsed() {
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(mock(AgentRuntimeService.class), new AgentStrategySelector());

        AgentCoordinationResult result = coordinator.coordinate(AgentCoordinationRequest.legacyPlanReflect(
                request("please propose a plan", List.of("search_web"))));

        assertThat(result.runtimeResult().stopReason()).isEqualTo(AgentStopReason.POLICY_REJECTED);
        assertThat(result.runtimeResult().success()).isFalse();
    }

    @Test
    void rejectsUnresolvedEndpointBeforeRuntimeExecution() {
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(mock(AgentRuntimeService.class), new AgentStrategySelector());
        AgentRuntimeRequest request = new AgentRuntimeRequest(null, 1L, List.of(), 1L, "hello", "", "model",
                null, null, 1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, new ResolvedToolPolicy(List.of(), 0, 0, "test"),
                null, null, "trace", null, null);

        AgentCoordinationResult result = coordinator.coordinate(AgentCoordinationRequest.chat(request));

        assertThat(result.runtimeResult().stopReason()).isEqualTo(AgentStopReason.POLICY_REJECTED);
        assertThat(result.runtimeResult().success()).isFalse();
    }

    @Test
    void classifiesUsableAnswerAfterToolBudgetExhaustionAsBudgetStop() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        when(runtime.run(org.mockito.ArgumentMatchers.any())).thenReturn(new AgentRuntimeResult(
                true, "partial answer", List.of(ChatMessage.assistant("partial answer")), 1, null,
                List.of(), List.of(), null, null, null)
                .withRuntimeStopSignal(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED));
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());

        AgentCoordinationResult result = coordinator.coordinate(AgentCoordinationRequest.chat(request("research", List.of("search_web"))));

        assertThat(result.runtimeResult().success()).isTrue();
        assertThat(result.runtimeResult().stopReason()).isEqualTo(AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED);
        assertThat(result.runtimeResult().outcome()).isEqualTo("BUDGET_STOP");
    }

    @Test
    void classifiesMaxStepsBudgetWithoutInspectingErrorText() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        when(runtime.run(org.mockito.ArgumentMatchers.any())).thenReturn(new AgentRuntimeResult(
                false, null, List.of(), 2, "arbitrary adapter error", List.of(), List.of(), null, null, null)
                .withRuntimeStopSignal(AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED));
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());

        AgentCoordinationResult result = coordinator.coordinate(AgentCoordinationRequest.chat(request("research", List.of("search_web"))));

        assertThat(result.runtimeResult().stopReason()).isEqualTo(AgentStopReason.MAX_STEPS_BUDGET_EXHAUSTED);
        assertThat(result.runtimeResult().outcome()).isEqualTo("BUDGET_STOP");
    }

    @Test
    void classifiesOnlyMissingAdapterAsNoRuntimeAdapter() {
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(
                new AgentRuntimeService(List.of()), new AgentStrategySelector());

        AgentCoordinationResult result = coordinator.coordinate(AgentCoordinationRequest.chat(request("hello", List.of())));

        assertThat(result.runtimeResult().stopReason()).isEqualTo(AgentStopReason.NO_RUNTIME_ADAPTER);
    }

    @Test
    void classifiesAdapterIllegalStateExceptionAsRuntimeException() {
        RuntimeAdapter brokenAdapter = new RuntimeAdapter() {
            @Override
            public boolean supports(AgentStrategy strategy) {
                return strategy == AgentStrategy.DIRECT;
            }

            @Override
            public AgentRuntimeResult run(AgentRuntimeRequest request) {
                throw new IllegalStateException("adapter failure");
            }
        };
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(
                new AgentRuntimeService(List.of(brokenAdapter)), new AgentStrategySelector());

        AgentCoordinationResult result = coordinator.coordinate(AgentCoordinationRequest.chat(request("hello", List.of())));

        assertThat(result.runtimeResult().stopReason()).isEqualTo(AgentStopReason.RUNTIME_EXCEPTION);
    }

    private AgentRuntimeRequest request(String message, List<String> tools) {
        return new AgentRuntimeRequest(null, 1L, List.of(), 1L, message, "test", "model", null, null, 2,
                false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, new ResolvedToolPolicy(tools, 2, 1, "test"),
                null, null, "trace", null, null);
    }
}
