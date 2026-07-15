package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.model.ChatMessage;
import com.yanban.core.agent.AgentTaskOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeCoordinatorTest {

    @Test
    void productionWorkspaceAssemblyCoversChatReactProjectAndPersistedPlanWithoutPolicyExpansion() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        when(runtime.run(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            AgentRuntimeRequest executed = invocation.getArgument(0);
            AgentRuntimeResult result = new AgentRuntimeResult(true, "answer-" + executed.strategy(), List.of(), 2,
                    null, List.of("observed"), List.of(), null, null, null);
            return executed.planId() == null ? result : result.withPlanId(executed.planId());
        });
        AgentRuntimeCoordinator coordinator = coordinator(runtime);
        AgentRuntimeRequest directRequest = request("hello", List.of("read"), "chat");
        AgentRuntimeRequest reactRequest = request("look it up", List.of("read"), "react");
        AgentRuntimeRequest projectRequest = request("inspect project", List.of("read"), "project")
                .withProjectContext(new ProjectRuntimeContext(1L, 42L));
        AgentRuntimeRequest planRequest = request("persisted plan", List.of("read"), "plan");

        List<AgentCoordinationResult> results = List.of(
                coordinator.coordinate(AgentCoordinationRequest.chat(directRequest)),
                coordinator.coordinate(AgentCoordinationRequest.chat(reactRequest)),
                coordinator.coordinate(AgentCoordinationRequest.projectRead(projectRequest)),
                coordinator.coordinate(AgentCoordinationRequest.trustedPlanApi(planRequest, 91L)));

        assertThat(results).allSatisfy(this::assertCanonicalWorkspace);
        assertThat(results.get(2).taskWorkspace().identity().projectId()).isEqualTo(42L);
        assertThat(results.get(3).taskWorkspace().identity().sourceId()).isEqualTo("91");
        assertThat(results.get(3).taskWorkspace().planReferences()).containsExactly(
                "Canonical run reference: AGENT_PLAN:91", "Selected strategy: PLAN_EXECUTE");
        assertThat(results.get(3).taskWorkspace().observedStepSummaries()).containsExactly("Runtime steps observed: 2");
        org.mockito.ArgumentCaptor<AgentRuntimeRequest> captured = org.mockito.ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(runtime, org.mockito.Mockito.times(4)).run(captured.capture());
        assertThat(captured.getAllValues()).allSatisfy(r -> assertThat(r.allowedToolNames()).containsExactly("read"));
    }

    @Test
    void productionWorkspaceAssemblyCoversRejectedPartialAndWaitingStates() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        when(runtime.run(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AgentRuntimeResult(true, "useful partial", List.of(), 1, null, List.of(), List.of(), null, null, null)
                        .withRuntimeStopSignal(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED))
                .thenReturn(new AgentRuntimeResult(false, null, List.of(), 1, null, List.of(), List.of(), null, null, null)
                        .withCoordination(AgentStrategy.DIRECT, AgentStopReason.WAITING_FOR_USER, "WAITING", false, null));
        AgentRuntimeCoordinator coordinator = coordinator(runtime);

        AgentCoordinationResult rejected = coordinator.coordinate(AgentCoordinationRequest.projectRead(request("project", List.of("read"), "reject")));
        AgentCoordinationResult partial = coordinator.coordinate(AgentCoordinationRequest.chat(request("research", List.of("read"), "partial")));
        AgentCoordinationResult waiting = coordinator.coordinate(AgentCoordinationRequest.chat(request("clarify", List.of("read"), "waiting")));

        assertCanonicalWorkspace(rejected);
        assertCanonicalWorkspace(partial);
        assertCanonicalWorkspace(waiting);
        assertThat(rejected.taskWorkspace().state().outcome()).isEqualTo(AgentTaskOutcome.FAILED);
        assertThat(partial.taskWorkspace().state().outcome()).isEqualTo(AgentTaskOutcome.PARTIAL);
        assertThat(waiting.taskWorkspace().state().status().name()).isEqualTo("WAITING_INPUT");
    }

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
        assertThat(direct.runProjection().state().outcome()).isEqualTo(AgentTaskOutcome.SUCCEEDED);
        assertThat(react.runProjection().state().outcome()).isEqualTo(AgentTaskOutcome.SUCCEEDED);
        assertThat(legacy.runProjection().state().outcome()).isEqualTo(AgentTaskOutcome.SUCCEEDED);
        assertThat(direct.runProjection().identity().source()).isEqualTo("RUNTIME_TRACE");
        assertThat(react.runProjection().identity().source()).isEqualTo("RUNTIME_TRACE");
        assertThat(legacy.runProjection().identity().source()).isEqualTo("RUNTIME_TRACE");
        assertThat(direct.runProjection().identity().runId()).isEqualTo("RUNTIME_TRACE:trace");
    }

    @Test
    void trustedPlanApiSelectsPlanExecuteWithoutNaturalLanguagePromotion() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        when(runtime.run(org.mockito.ArgumentMatchers.any())).thenReturn(new AgentRuntimeResult(
                true, "ok", List.of(ChatMessage.assistant("ok")), 1, null, List.of(), List.of(), null, null, null)
                .withPlanId(91L));
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());

        AgentCoordinationResult result = coordinator.coordinate(AgentCoordinationRequest.trustedPlanApi(
                request("ordinary persisted plan goal", List.of()), 91L));

        assertThat(result.decision().selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(result.runtimeResult().selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        org.mockito.ArgumentCaptor<AgentRuntimeRequest> captured = org.mockito.ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(runtime).run(captured.capture());
        assertThat(captured.getValue().planId()).isEqualTo(91L);
        assertThat(result.runProjection().identity().source()).isEqualTo("AGENT_PLAN");
        assertThat(result.runProjection().identity().sourceId()).isEqualTo("91");
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

    @Test
    void sameSessionRunsRequireDistinctTraceIdentities() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        when(runtime.run(org.mockito.ArgumentMatchers.any())).thenReturn(new AgentRuntimeResult(
                true, "ok", List.of(), 1, null, List.of(), List.of(), null, null, null));
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());

        AgentCoordinationResult first = coordinator.coordinate(AgentCoordinationRequest.chat(
                request("first", List.of(), "trace-1")));
        AgentCoordinationResult second = coordinator.coordinate(AgentCoordinationRequest.chat(
                request("second", List.of(), "trace-2")));

        assertThat(first.runProjection().identity().runId()).isNotEqualTo(second.runProjection().identity().runId());
    }

    @Test
    void missingAndBlankTraceReturnStablePolicyRejectionsWithUniqueInvocationIdentities() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());

        AgentCoordinationResult missing = coordinator.coordinate(AgentCoordinationRequest.chat(
                request("first", List.of(), null)));
        AgentCoordinationResult blank = coordinator.coordinate(AgentCoordinationRequest.chat(
                request("second", List.of(), "   ")));

        assertThat(missing.runtimeResult().stopReason()).isEqualTo(AgentStopReason.POLICY_REJECTED);
        assertThat(blank.runtimeResult().stopReason()).isEqualTo(AgentStopReason.POLICY_REJECTED);
        assertThat(missing.runProjection().identity().source()).isEqualTo("RUNTIME_INVOCATION");
        assertThat(blank.runProjection().identity().source()).isEqualTo("RUNTIME_INVOCATION");
        assertThat(missing.runProjection().identity().runId())
                .isNotEqualTo(blank.runProjection().identity().runId());
        verify(runtime, org.mockito.Mockito.never()).run(org.mockito.ArgumentMatchers.any());
    }

    private AgentRuntimeRequest request(String message, List<String> tools) {
        return request(message, tools, "trace");
    }

    private AgentRuntimeRequest request(String message, List<String> tools, String traceId) {
        return new AgentRuntimeRequest(null, 1L, List.of(), 1L, message, "test", "model", null, null, 2,
                false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, new ResolvedToolPolicy(tools, 2, 1, "test"),
                null, null, traceId, null, null);
    }

    private AgentRuntimeCoordinator coordinator(AgentRuntimeService runtime) {
        return new AgentRuntimeCoordinator(runtime, new AgentStrategySelector(),
                new AgentTaskWorkspaceService(new ObjectMapper()));
    }

    private void assertCanonicalWorkspace(AgentCoordinationResult result) {
        assertThat(result.taskWorkspace()).isNotNull();
        assertThat(result.taskWorkspace().identity()).isEqualTo(result.runProjection().identity());
        assertThat(result.taskWorkspace().state()).isEqualTo(result.runProjection().state());
        assertThat(result.taskWorkspace().canonicalAnswer()).isEqualTo(result.runProjection().canonicalAnswer());
        assertThat(result.taskWorkspace().persistenceLevel()).isEqualTo("L0_REQUEST_BOUND");
        assertThat(result.taskWorkspace().checkpointAvailable()).isFalse();
        assertThat(result.taskWorkspace().restartResumable()).isFalse();
    }
}
