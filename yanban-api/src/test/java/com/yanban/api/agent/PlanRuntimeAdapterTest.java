package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
    }

    @Test
    void creationUsesTheSamePlanAdapterAndReturnsThePersistedPlanIdentity() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan("REVIEWING", null, List.of()));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(requestWithoutPlanId());

        assertThat(result.success()).isTrue();
        assertThat(result.planId()).isEqualTo(19L);
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
        EvidenceLedger current = new EvidenceLedger(List.of(new EvidenceRef("trusted-plan:42:src/Main.java:h1:step",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "step", null, "h1", "event")));
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", true)).thenReturn(
                new PlanAgentService.PlanExecutionResult(plan("COMPLETED", null, List.of(step("COMPLETED"))),
                        AgentRuntimeStopSignal.NONE, current));
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, "m", List.of(
                new ProjectFileEntry("src/Main.java", 1, Instant.EPOCH, "h1"))));
        CompletionVerifier verifier = new CompletionVerifier(new ObjectMapper(), new ProjectEvidenceValidator(projects),
                mock(CandidateChangeArtifactService.class));
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(new PlanRuntimeAdapter(service)), verifier,
                new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult verified = runtime.run(projectRequest());

        assertThat(verified.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    private static AgentRuntimeRequest requestWithoutPlanId() {
        return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 11L, List.of(), 7L, "new plan", "test", "model",
                null, null, 1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, List.of(), 0, 0, "trace", null, null);
    }

    private static AgentRuntimeRequest projectRequest() {
        return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 11L, List.of(), 7L, "inspect", "test", "model",
                null, null, 1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 1, 1, "project"), null, null, "trace", null, null)
                .withPlanId(19L).withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private static AgentPlanResponse plan(String status, String error, List<AgentPlanStepResponse> steps) {
        return new AgentPlanResponse(19L, 11L, "goal", "persuasive summary", status, false, null, error,
                null, null, null, null, steps);
    }

    private static AgentPlanStepResponse step(String status) {
        return new AgentPlanStepResponse(1L, "step_1", 1, "title", "description", "ANALYSIS", List.of(), List.of(),
                "done", status, 1, null, null, null, null);
    }
}
