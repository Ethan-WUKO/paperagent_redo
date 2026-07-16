package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanRuntimeAdapterTest {

    @Test
    void mapsPersistedPlanTerminalStatesWithoutTrustingTheSummaryText() {
        assertThat(PlanRuntimeAdapter.classify(plan("COMPLETED", null, List.of())).outcome()).isEqualTo("SUCCESS");
        assertThat(PlanRuntimeAdapter.classify(plan("COMPLETED", null, List.of(step("DEGRADED")))).outcome())
                .isEqualTo("PARTIAL");
        assertThat(PlanRuntimeAdapter.classify(plan("FAILED", "model says everything is fine", List.of())).outcome())
                .isEqualTo("FAILURE");
        assertThat(PlanRuntimeAdapter.classify(plan("PAUSED", null, List.of())).outcome()).isEqualTo("PAUSED");
        assertThat(PlanRuntimeAdapter.classify(plan("RUNNING", null, List.of())).outcome()).isEqualTo("WAITING");
        assertThat(PlanRuntimeAdapter.classify(plan("FAILED", "arbitrary error text", List.of()),
                AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED).outcome()).isEqualTo("BUDGET_STOP");
    }

    @Test
    void adapterDoesNotReportFailedPlanAsSuccessfulChatAnswer() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", true))
                .thenReturn(new PlanAgentService.PlanExecutionResult(
                        plan("FAILED", "step failed", List.of()), AgentRuntimeStopSignal.NONE));
        PlanRuntimeAdapter adapter = new PlanRuntimeAdapter(service);

        AgentRuntimeResult result = adapter.run(new AgentRuntimeRequest(
                AgentStrategy.PLAN_EXECUTE, 11L, List.of(), 7L, "persisted goal", "test", "model", null, null,
                1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, List.of(), 0, 0, "trace", null, null).withPlanId(19L));

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("FAILURE");
        assertThat(result.planId()).isEqualTo(19L);
        verify(service).executePlanResultWithinAdapter(7L, 19L, "trace", true);
        verify(service, never()).createPlanWithinAdapter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void trustedCreateWithAutoLikeTextDoesNotExecute() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan("REVIEWING", null, List.of()));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(requestWithoutPlanId());

        assertThat(result.success()).isTrue();
        assertThat(result.planId()).isEqualTo(19L);
        assertThat(result.outcome()).isEqualTo("PLAN_CREATED");
        verify(service, never()).executePlanResultWithinAdapter(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void existingPlanIdKeepsConversationSummaryPersistenceEvenWithServerAutoAudit() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", true))
                .thenReturn(new PlanAgentService.PlanExecutionResult(
                        plan("COMPLETED", null, List.of(step("COMPLETED", "Existing plan result", null))),
                        AgentRuntimeStopSignal.NONE));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(autoProjectRequest().withPlanId(19L));

        assertThat(result.success()).isTrue();
        assertThat(result.planId()).isEqualTo(19L);
        verify(service).executePlanResultWithinAdapter(7L, 19L, "trace", true);
        verify(service, never()).executePlanResultWithinAdapter(7L, 19L, "trace", false);
        verify(service, never()).createPlanWithinAdapter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void serverAutoProjectPlanIsCreatedAndExecutedWithRealStepResults() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan("REVIEWING", null, List.of()));
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", false))
                .thenReturn(new PlanAgentService.PlanExecutionResult(plan("COMPLETED", null,
                        List.of(step("COMPLETED", "Cross-material analysis result", null))),
                        AgentRuntimeStopSignal.NONE));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(autoProjectRequest());

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.planId()).isEqualTo(19L);
        assertThat(result.assistantContent()).contains("status COMPLETED", "Cross-material analysis result");
        verify(service).createPlanWithinAdapter(org.mockito.ArgumentMatchers.any());
        verify(service).executePlanResultWithinAdapter(7L, 19L, "trace", false);
        verify(service, never()).executePlanResultWithinAdapter(7L, 19L, "trace", true);
    }

    @Test
    void serverAutoProjectPlanReportsControlledPartialWithoutClaimingSuccess() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan("REVIEWING", null, List.of()));
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", false))
                .thenReturn(new PlanAgentService.PlanExecutionResult(plan("COMPLETED", null,
                        List.of(step("DEGRADED", "Partial governed result", "coverage limited"))),
                        AgentRuntimeStopSignal.NONE));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(autoProjectRequest());

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.PLAN_PARTIAL);
        assertThat(result.degraded()).isTrue();
        assertThat(result.planId()).isEqualTo(19L);
        assertThat(result.assistantContent()).contains("Partial governed result");
    }

    @Test
    void serverAutoProjectPlanExecutionFailureRetainsCreatedPlanIdentity() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan("REVIEWING", null, List.of()));
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", false))
                .thenThrow(new IllegalStateException("execution unavailable"));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(autoProjectRequest());

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("FAILURE");
        assertThat(result.planId()).isEqualTo(19L);
        assertThat(result.errorMessage()).contains("execution unavailable");
    }

    @Test
    void budgetSignalIsStructuredAndIndependentOfPlanErrorText() {
        PlanRuntimeAdapter.PlanTerminal terminal = PlanRuntimeAdapter.classify(
                plan("FAILED", "arbitrary wording", List.of()), AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED);

        assertThat(terminal.outcome()).isEqualTo("BUDGET_STOP");
    }

    @Test
    void outerVerifierUsesTypedPlanEvidenceAndRejectsMissingOrOldEvidence() {
        PlanAgentService service = mock(PlanAgentService.class);
        String projectVersion = "b".repeat(64); String fileHash = "a".repeat(64);
        EvidenceLedger current = new EvidenceLedger(List.of(new EvidenceRef("trusted-plan:42:src/Main.java:h1:step",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "step", null, fileHash, "event",
                projectVersion, fileHash, 3, 3, "project-search@1", EvidenceVersionStatus.VERIFIED)));
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", true)).thenReturn(
                new PlanAgentService.PlanExecutionResult(plan("COMPLETED", null, List.of(step("COMPLETED"))),
                        AgentRuntimeStopSignal.NONE, current));
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, projectVersion, List.of(
                new ProjectFileEntry("src/Main.java", 1, Instant.EPOCH, fileHash))));
        CompletionVerifier verifier = new CompletionVerifier(new ObjectMapper(), new ProjectEvidenceValidator(projects),
                mock(CandidateChangeArtifactService.class));
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(new PlanRuntimeAdapter(service)), verifier,
                new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult verified = runtime.run(projectRequest());

        assertThat(verified.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    private static AgentRuntimeRequest requestWithoutPlanId() {
        AgentOrchestrationRequirements trustedCreate = new AgentOrchestrationRequirements(List.of(),
                List.of(AgentStrategyReasonCode.TRUSTED_PLAN_CAPABILITY), List.of(),
                AgentStrategySelectionOrigin.TRUSTED_CAPABILITY);
        return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 11L, List.of(), 7L, "cross material", "test", "model",
                null, null, 1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, List.of(), 0, 0, "trace", null, null)
                .withOrchestrationRequirements(trustedCreate);
    }

    private static AgentRuntimeRequest projectRequest() {
        return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 11L, List.of(), 7L, "inspect", "test", "model",
                null, null, 1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 1, 1, "project"), null, null, "trace", null, null)
                .withPlanId(19L).withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private static AgentRuntimeRequest autoProjectRequest() {
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN), List.of(),
                AgentStrategySelectionOrigin.SERVER_AUTO);
        return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 11L, List.of(), 7L, "cross material", "test", "model",
                null, null, 4, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 4, 1, "project"), 4, 1,
                "trace", null, null)
                .withProjectContext(new ProjectRuntimeContext(7L, 42L))
                .withOrchestrationRequirements(audit);
    }

    private static AgentPlanResponse plan(String status, String error, List<AgentPlanStepResponse> steps) {
        return new AgentPlanResponse(19L, 11L, "goal", "persuasive summary", status, false, null, error,
                null, null, null, null, steps);
    }

    private static AgentPlanStepResponse step(String status) {
        return step(status, null, null);
    }

    private static AgentPlanStepResponse step(String status, String result, String error) {
        return new AgentPlanStepResponse(1L, "step_1", 1, "title", "description", "ANALYSIS", List.of(), List.of(),
                "done", status, 1, result, error, null, null);
    }
}
