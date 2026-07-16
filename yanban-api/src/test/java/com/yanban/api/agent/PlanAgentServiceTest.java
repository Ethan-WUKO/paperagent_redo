package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.project.ProjectService;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.SkillsService;
import com.yanban.api.skills.ResolvedSkill;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanStatus;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepRepository;
import com.yanban.core.agent.AgentPlanStepStatus;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PlanAgentServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 11L;
    private static final Long PLAN_ID = 19L;

    @Mock
    AgentPlanRepository plans;

    @Mock
    AgentPlanStepRepository steps;

    @Mock
    AgentPlanEventRepository events;

    @Mock
    AgentService agentService;

    @Mock
    PlanningAgentPlanner planner;

    @Mock
    PlanStepVerifier stepVerifier;

    @Mock
    AgentRuntimeService agentRuntimeService;

    @Mock
    AgentRuntimeCoordinator runtimeCoordinator;

    @Mock
    UserSettingsService userSettingsService;

    @Mock
    SkillsService skillsService;

    @Mock
    AgentToolPolicyEngine toolPolicyEngine;

    @Mock
    ProjectService projectService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PlanAgentService service;
    private AgentPlan plan;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        service = new PlanAgentService(
                plans,
                steps,
                events,
                agentService,
                agentRuntimeService,
                null,
                planner,
                stepVerifier,
                userSettingsService,
                skillsService,
                toolPolicyEngine,
                objectMapper,
                projectService
        );
        plan = newPlan();
        session = newSession();

        when(plans.findByIdAndUserId(PLAN_ID, USER_ID)).thenReturn(Optional.of(plan));
        when(agentService.getOwnedSession(USER_ID, SESSION_ID)).thenReturn(session);
        when(userSettingsService.resolveModelEndpoint(anyLong(), any(), any()))
                .thenReturn(new UserSettingsService.ModelEndpoint(
                        UserSettingsService.DEFAULT_PROVIDER,
                        UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                        null,
                        "test-api-key",
                        "builtin",
                        "DeepSeek"));
        when(plans.saveAndFlush(any(AgentPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(steps.saveAndFlush(any(AgentPlanStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(events.save(any(AgentPlanEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(toolPolicyEngine.decide(any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new AgentToolPolicyEngine.Decision(List.of(), 3, 1, "test_plan_policy"));
        lenient().when(projectService.manifest(anyLong(), anyLong()))
                .thenReturn(new ProjectManifestResponse(42L, "manifest-test", List.of()));
    }

    @Test
    void executePlanRunsStepsInDependencyOrderAndPassesDependencyResult() {
        AgentPlanStep first = newStep("step_1", 1, List.of());
        AgentPlanStep second = newStep("step_2", 2, List.of("step_1"));
        List<AgentPlanStep> orderedSteps = List.of(first, second);
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(orderedSteps);
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(successWithTrace("analysis result",
                        "step=1 tool=project_search args={\"query\":\"objective\"} success=true"))
                .thenReturn(success("final result"));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(response.steps()).extracting(AgentPlanStepResponse::status)
                .containsExactly(AgentPlanStepStatus.COMPLETED.name(), AgentPlanStepStatus.COMPLETED.name());
        assertThat(first.getResult()).isEqualTo("analysis result");
        assertThat(second.getResult()).isEqualTo("final result");

        ArgumentCaptor<AgentRuntimeRequest> requestCaptor = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService, times(2)).run(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues().get(1).history().get(0).content())
                .contains("analysis result")
                .contains("Reusable tool observations from step_1")
                .contains("project_search")
                .contains("Do not repeat successful or zero-result searches");
        verify(stepVerifier, times(2)).verify(any(PlanStepVerifier.VerificationRequest.class));
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void scheduleAsyncExecutionWaitsUntilAfterCommitWhenTransactionIsActive() {
        List<String> scheduled = new ArrayList<>();
        PlanAgentService probe = new PlanAgentService(
                plans, steps, events, agentService, agentRuntimeService, null, planner, stepVerifier,
                userSettingsService, skillsService, toolPolicyEngine, objectMapper, projectService) {
            @Override
            void submitAsyncExecutionTask(Long userId, Long planId, String traceId) {
                scheduled.add(userId + ":" + planId + ":" + traceId);
            }
        };

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            probe.scheduleAsyncExecution(USER_ID, PLAN_ID, "trace-1");
            assertThat(scheduled).isEmpty();
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            assertThat(scheduled).containsExactly(USER_ID + ":" + PLAN_ID + ":trace-1");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void scheduleAsyncExecutionStartsImmediatelyWithoutTransaction() {
        List<String> scheduled = new ArrayList<>();
        PlanAgentService probe = new PlanAgentService(
                plans, steps, events, agentService, agentRuntimeService, null, planner, stepVerifier,
                userSettingsService, skillsService, toolPolicyEngine, objectMapper, projectService) {
            @Override
            void submitAsyncExecutionTask(Long userId, Long planId, String traceId) {
                scheduled.add(userId + ":" + planId + ":" + traceId);
            }
        };

        probe.scheduleAsyncExecution(USER_ID, PLAN_ID, "trace-2");

        assertThat(scheduled).containsExactly(USER_ID + ":" + PLAN_ID + ":trace-2");
    }

    @Test
    void projectResearchStepPersistsTrustedEvidenceThroughExecutePlan() throws Exception {
        String hash = "a".repeat(64);
        ReflectionTestUtils.setField(plan, "rawPlanJson", ProjectPlanEnvelope.wrap(objectMapper, "{}", new ProjectRuntimeContext(USER_ID, 42L)));
        AgentPlanStep step = newStep("research", 1, List.of(), "[\"project_latex_outline\"]");
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(projectService.manifest(USER_ID, 42L)).thenReturn(new ProjectManifestResponse(42L, "b".repeat(64), List.of(
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, hash))));
        when(toolPolicyEngine.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(
                List.of("project_latex_outline"), 3, 1, "project"));
        com.yanban.core.model.ToolCall call = new com.yanban.core.model.ToolCall("research-call", "function",
                new com.yanban.core.model.ToolCall.FunctionCall("project_latex_outline", "{\"relativePaths\":[\"paper/main.tex\"]}"));
        String envelope = "{\"status\":\"COMPLETE\",\"items\":[],\"evidenceRefs\":[{\"projectVersion\":\"" + "b".repeat(64)
                + "\",\"relativePath\":\"paper/main.tex\",\"fileHash\":\"" + hash
                + "\",\"range\":{\"startLine\":1,\"endLine\":1},\"parserVersion\":\"latex-outline@1\",\"trustLabel\":\"SERVER_ATTESTED_METADATA\"}]}";
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class))).thenReturn(new AgentRuntimeResult(true, "observed",
                List.of(new ChatMessage("assistant", null, List.of(call), null), ChatMessage.tool("research-call", envelope)),
                1, null, List.of(), List.of(), null, null, null));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentRuntimeRequest> request = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService).run(request.capture());
        assertThat(request.getValue().projectContext()).isEqualTo(new ProjectRuntimeContext(USER_ID, 42L));
        assertThat(request.getValue().toolPolicy().allowedTools()).containsExactly("project_latex_outline");
        assertThat(request.getValue().history().get(0).content())
                .contains("Server-cached Project manifest")
                .contains("paper/main.tex")
                .contains("do not call project_manifest again");
        ArgumentCaptor<AgentPlanEvent> event = ArgumentCaptor.forClass(AgentPlanEvent.class); verify(events, atLeast(1)).save(event.capture());
        AgentPlanEvent evidenceEvent = event.getAllValues().stream().filter(value -> "step_project_evidence".equals(value.getEventType())).findFirst().orElseThrow();
        var payload = objectMapper.readTree(evidenceEvent.getPayloadJson()); var evidence = payload.path("evidence").get(0);
        assertThat(evidence.path("id").asText()).startsWith("trusted-plan:"); assertThat(evidence.path("file").asText()).isEqualTo("paper/main.tex");
        assertThat(evidence.path("version").asText()).isEqualTo(hash); assertThat(evidenceEvent.getPayloadJson()).doesNotContain("host", "item prose");
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void listProjectEvidenceMarksPersistedResearchEvidenceStaleAfterManifestChange() throws Exception {
        String current = "c".repeat(64); String stale = "d".repeat(64);
        ReflectionTestUtils.setField(plan, "rawPlanJson", ProjectPlanEnvelope.wrap(objectMapper, "{}", new ProjectRuntimeContext(USER_ID, 42L)));
        EvidenceRef ref = new EvidenceRef("trusted-plan:42:paper/main.tex:" + current + ":step", EvidenceSourceType.PROJECT,
                "PROJECT", "paper/main.tex", "tool:step", null, current, "test");
        AgentPlanEvent event = new AgentPlanEvent(PLAN_ID, 1L, "step_project_evidence",
                objectMapper.writeValueAsString(java.util.Map.of("evidence", List.of(ref))));
        when(events.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenReturn(List.of(event));
        when(projectService.manifest(USER_ID, 42L)).thenReturn(new ProjectManifestResponse(42L, "m", List.of(
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, current))));
        assertThat(service.listProjectEvidence(USER_ID, 42L, PLAN_ID)).singleElement().satisfies(value -> assertThat(value.current()).isTrue());
        when(projectService.manifest(USER_ID, 42L)).thenReturn(new ProjectManifestResponse(42L, "m2", List.of(
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, stale))));
        assertThat(service.listProjectEvidence(USER_ID, 42L, PLAN_ID)).singleElement().satisfies(value -> assertThat(value.current()).isFalse());
        assertThatThrownBy(() -> service.listProjectEvidence(USER_ID, 99L, PLAN_ID)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void emptyPreferredToolsStillExposeLowRiskReadOnlyProjectTools() {
        String hash = "a".repeat(64);
        ReflectionTestUtils.setField(plan, "rawPlanJson", ProjectPlanEnvelope.wrap(objectMapper, "{}", new ProjectRuntimeContext(USER_ID, 42L)));
        AgentPlanStep step = newStep("deny", 1, List.of(), "[]");
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(projectService.manifest(USER_ID, 42L)).thenReturn(new ProjectManifestResponse(42L, "m", List.of(
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, hash))));
        when(toolPolicyEngine.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(List.of("project_latex_outline"), 3, 1, "project"));
        String envelope = "{\"status\":\"COMPLETE\",\"items\":[],\"evidenceRefs\":[{\"projectVersion\":\"" + "b".repeat(64)
                + "\",\"relativePath\":\"paper/main.tex\",\"fileHash\":\"" + hash + "\",\"range\":{\"startLine\":1,\"endLine\":1},\"parserVersion\":\"latex-outline@1\",\"trustLabel\":\"SERVER_ATTESTED_METADATA\"}]}";
        com.yanban.core.model.ToolCall call = new com.yanban.core.model.ToolCall("deny-call", "function",
                new com.yanban.core.model.ToolCall.FunctionCall("project_latex_outline", "{}"));
        when(agentRuntimeService.run(any())).thenReturn(new AgentRuntimeResult(true, "result", List.of(
                new ChatMessage("assistant", null, List.of(call), null), ChatMessage.tool("deny-call", envelope)), 1, null, List.of(), List.of(), null, null, null));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentRuntimeRequest> request = ArgumentCaptor.forClass(AgentRuntimeRequest.class); verify(agentRuntimeService, atLeast(1)).run(request.capture());
        assertThat(request.getAllValues()).allSatisfy(value -> assertThat(value.toolPolicy().allowedTools())
                .containsExactly("project_latex_outline"));
        assertThat(request.getValue().toolPolicy().reason()).isEqualTo("project_plan_low_risk_read_only_tools");
        ArgumentCaptor<AgentPlanEvent> event = ArgumentCaptor.forClass(AgentPlanEvent.class); verify(events, atLeast(1)).save(event.capture());
        assertThat(event.getAllValues()).anyMatch(value -> "step_project_evidence".equals(value.getEventType()));
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void projectSynthesisMayChooseNoToolWhileAllLowRiskToolsRemainAvailable() throws Exception {
        String hash = "a".repeat(64);
        ReflectionTestUtils.setField(plan, "rawPlanJson", ProjectPlanEnvelope.wrap(
                objectMapper, "{}", new ProjectRuntimeContext(USER_ID, 42L)));
        AgentPlanStep research = newStep("research", 1, List.of(), "[\"project_latex_outline\"]");
        research.markCompleted("trusted research result");
        AgentPlanStep synthesis = newStep("synthesis", 2, List.of("research"), "[]");
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(research, synthesis));
        when(projectService.manifest(USER_ID, 42L)).thenReturn(new ProjectManifestResponse(
                42L, "m", List.of(new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, hash))));
        when(toolPolicyEngine.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(
                List.of("project_latex_outline", "project_search"), 8, 1, "project"));
        EvidenceRef ref = new EvidenceRef("trusted-plan:42:paper/main.tex:" + hash + ":research",
                EvidenceSourceType.PROJECT, "PROJECT", "paper/main.tex", "tool:research", null, hash, "test");
        AgentPlanEvent evidenceEvent = new AgentPlanEvent(PLAN_ID, research.getId(), "step_project_evidence",
                objectMapper.writeValueAsString(java.util.Map.of("evidence", List.of(ref))));
        when(events.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenReturn(List.of(evidenceEvent));
        when(agentRuntimeService.run(any())).thenReturn(success("synthesized from dependency"));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentRuntimeRequest> request = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService).run(request.capture());
        assertThat(request.getValue().toolPolicy().allowedTools())
                .containsExactly("project_latex_outline", "project_search");
        assertThat(request.getValue().inheritedTrustedEvidence().evidence())
                .containsExactly(ref);
        assertThat(request.getValue().history().get(0).content())
                .contains("trusted research result")
                .contains("planning hints, not the complete tool set");
        assertThat(synthesis.getStatus()).isEqualTo(AgentPlanStepStatus.COMPLETED.name());
        ArgumentCaptor<AgentPlanEvent> recorded = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, atLeast(1)).save(recorded.capture());
        assertThat(recorded.getAllValues()).anyMatch(value ->
                "step_dependency_evidence_reused".equals(value.getEventType()));
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void projectSummaryWithInheritedEvidenceDoesNotRerunWhenVerifierRejectsCoverage() throws Exception {
        String hash = "d".repeat(64);
        ReflectionTestUtils.setField(plan, "rawPlanJson", ProjectPlanEnvelope.wrap(
                objectMapper, "{}", new ProjectRuntimeContext(USER_ID, 42L)));
        AgentPlanStep research = newStep("research", 1, List.of(), "[\"project_search\"]");
        research.markCompleted("trusted research result");
        AgentPlanStep summary = newStep("summary", 2, List.of("research"), "[]");
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(research, summary));
        when(projectService.manifest(USER_ID, 42L)).thenReturn(new ProjectManifestResponse(
                42L, "m", List.of(new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, hash))));
        when(toolPolicyEngine.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(
                List.of("project_search", "project_read_file"), 8, 1, "project"));
        EvidenceRef ref = new EvidenceRef("trusted-plan:42:paper/main.tex:" + hash + ":research",
                EvidenceSourceType.PROJECT, "PROJECT", "paper/main.tex", "tool:research", null, hash, "test");
        AgentPlanEvent evidenceEvent = new AgentPlanEvent(PLAN_ID, research.getId(), "step_project_evidence",
                objectMapper.writeValueAsString(java.util.Map.of("evidence", List.of(ref))));
        when(events.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenReturn(List.of(evidenceEvent));
        AgentRuntimeResult groundedSummary = success("usable dependency summary")
                .withTrustedEvidenceLedger(new EvidenceLedger(List.of(ref)));
        when(agentRuntimeService.run(any())).thenReturn(groundedSummary);
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.failed("one criterion missing"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(summary.getStatus()).isEqualTo(AgentPlanStepStatus.DEGRADED.name());
        assertThat(summary.getAttemptCount()).isEqualTo(1);
        assertThat(summary.getResult()).isEqualTo("usable dependency summary");
        verify(agentRuntimeService).run(any(AgentRuntimeRequest.class));
        verify(planner, org.mockito.Mockito.never()).createRecoveryPlan(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void projectStepCannotPersistResearchEvidenceForADifferentAllowedTool() {
        String hash = "f".repeat(64);
        ReflectionTestUtils.setField(plan, "rawPlanJson", ProjectPlanEnvelope.wrap(objectMapper, "{}", new ProjectRuntimeContext(USER_ID, 42L)));
        AgentPlanStep step = newStep("latex-only", 1, List.of(), "[\"project_latex_outline\"]");
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(projectService.manifest(USER_ID, 42L)).thenReturn(new ProjectManifestResponse(42L, "m", List.of(
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, hash))));
        when(toolPolicyEngine.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(
                List.of("project_latex_outline"), 3, 1, "project"));
        com.yanban.core.model.ToolCall call = new com.yanban.core.model.ToolCall("bibtex-call", "function",
                new com.yanban.core.model.ToolCall.FunctionCall("project_bibtex_audit", "{\"relativePaths\":[\"paper/main.tex\"]}"));
        String envelope = "{\"status\":\"COMPLETE\",\"items\":[],\"evidenceRefs\":[{\"projectVersion\":\"" + "b".repeat(64)
                + "\",\"relativePath\":\"paper/main.tex\",\"fileHash\":\"" + hash
                + "\",\"range\":{\"startLine\":1,\"endLine\":1},\"parserVersion\":\"bibtex-audit@1\",\"trustLabel\":\"SERVER_ATTESTED_METADATA\"}]}";
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class))).thenReturn(new AgentRuntimeResult(true, "observed",
                List.of(new ChatMessage("assistant", null, List.of(call), null), ChatMessage.tool("bibtex-call", envelope)),
                1, null, List.of(), List.of(), null, null, null));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentRuntimeRequest> request = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService, times(2)).run(request.capture());
        assertThat(request.getAllValues()).allSatisfy(value -> assertThat(value.toolPolicy().allowedTools())
                .containsExactly("project_latex_outline"));
        ArgumentCaptor<AgentPlanEvent> event = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, atLeast(1)).save(event.capture());
        assertThat(event.getAllValues()).noneMatch(value -> "step_project_evidence".equals(value.getEventType())
                || (value.getPayloadJson() != null && value.getPayloadJson().contains("trusted-plan:")));
        assertThat(event.getAllValues()).extracting(AgentPlanEvent::getEventType).contains("step_evidence_insufficient");
    }

    @Test
    void projectReadFileStepPersistsCompleteVersionedProjectEvidenceThroughExecutePlan() throws Exception {
        String hash = "e".repeat(64);
        String projectVersion = "b".repeat(64);
        ReflectionTestUtils.setField(plan, "rawPlanJson", ProjectPlanEnvelope.wrap(objectMapper, "{}", new ProjectRuntimeContext(USER_ID, 42L)));
        AgentPlanStep step = newStep("versioned-read", 1, List.of(), "[\"project_read_file\"]");
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(projectService.manifest(USER_ID, 42L)).thenReturn(new ProjectManifestResponse(42L, projectVersion, List.of(
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, hash))));
        when(toolPolicyEngine.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(
                List.of("project_read_file"), 3, 1, "project"));
        com.yanban.core.model.ToolCall call = new com.yanban.core.model.ToolCall("versioned-read-call", "function",
                new com.yanban.core.model.ToolCall.FunctionCall("project_read_file", "{\"relativePath\":\"paper/main.tex\"}"));
        String result = "{\"projectId\":42,\"projectVersion\":\"" + projectVersion
                + "\",\"relativePath\":\"paper/main.tex\",\"hash\":\"" + hash
                + "\",\"version\":\"" + hash + "\",\"startLine\":3,\"endLine\":7,"
                + "\"evidenceRefs\":[\"versioned-read-ref\"]}";
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class))).thenReturn(new AgentRuntimeResult(true, "observed",
                List.of(new ChatMessage("assistant", null, List.of(call), null), ChatMessage.tool("versioned-read-call", result)),
                1, null, List.of(), List.of(), null, null, null));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentPlanEvent> eventsCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, atLeast(1)).save(eventsCaptor.capture());
        List<AgentPlanEvent> evidenceEvents = eventsCaptor.getAllValues().stream()
                .filter(value -> "step_project_evidence".equals(value.getEventType())).toList();
        assertThat(evidenceEvents).hasSize(1);
        var evidence = objectMapper.readTree(evidenceEvents.get(0).getPayloadJson()).path("evidence");
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).path("id").asText()).startsWith("trusted-plan:");
        assertThat(evidence.get(0).path("file").asText()).isEqualTo("paper/main.tex");
        assertThat(evidence.get(0).path("version").asText()).isEqualTo(hash);
        assertThat(evidence.get(0).path("projectVersion").asText()).isEqualTo(projectVersion);
        assertThat(evidence.get(0).path("fileHash").asText()).isEqualTo(hash);
        assertThat(evidence.get(0).path("startLine").asInt()).isEqualTo(3);
        assertThat(evidence.get(0).path("endLine").asInt()).isEqualTo(7);
        assertThat(evidence.get(0).path("parserVersion").asText()).isEqualTo("project-read-file@1");
        assertThat(evidence.get(0).path("versionStatus").asText()).isEqualTo("VERIFIED");
    }

    @Test
    void legacyProjectReadWithoutVersionAndRangeRemainsInsufficient() {
        String hash = "e".repeat(64);
        ReflectionTestUtils.setField(plan, "rawPlanJson", ProjectPlanEnvelope.wrap(
                objectMapper, "{}", new ProjectRuntimeContext(USER_ID, 42L)));
        AgentPlanStep step = newStep("legacy-read", 1, List.of(), "[\"project_read_file\"]");
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(projectService.manifest(USER_ID, 42L)).thenReturn(new ProjectManifestResponse(
                42L, "b".repeat(64), List.of(new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, hash))));
        when(toolPolicyEngine.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(
                List.of("project_read_file"), 3, 1, "project"));
        com.yanban.core.model.ToolCall call = new com.yanban.core.model.ToolCall("legacy-read-call", "function",
                new com.yanban.core.model.ToolCall.FunctionCall(
                        "project_read_file", "{\"relativePath\":\"paper/main.tex\"}"));
        String legacyResult = "{\"projectId\":42,\"relativePath\":\"paper/main.tex\",\"hash\":\"" + hash
                + "\",\"version\":\"" + hash + "\",\"evidenceRefs\":[\"legacy-read-ref\"]}";
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class))).thenReturn(new AgentRuntimeResult(
                true, "observed", List.of(new ChatMessage("assistant", null, List.of(call), null),
                ChatMessage.tool("legacy-read-call", legacyResult)), 1, null, List.of(), List.of(), null, null, null));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentPlanEvent> event = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, atLeast(1)).save(event.capture());
        assertThat(event.getAllValues()).filteredOn(value -> "step_project_evidence".equals(value.getEventType()))
                .allSatisfy(value -> assertThat(readEvidenceCount(value)).isZero());
        assertThat(event.getAllValues()).extracting(AgentPlanEvent::getEventType)
                .contains("step_verification_inconclusive", "step_completed_unverified")
                .doesNotContain("step_completed");
    }

    private int readEvidenceCount(AgentPlanEvent event) {
        try {
            return objectMapper.readTree(event.getPayloadJson()).path("evidence").size();
        } catch (Exception ignored) {
            return -1;
        }
    }

    @Test
    void projectStepNamespacesServerOwnedEvidenceForPlanWideUniqueness() throws Exception {
        String hash = "9".repeat(64);
        String trustedId = "trusted-tool:42:paper/main.tex:" + hash + ":runtime-call";
        ReflectionTestUtils.setField(plan, "rawPlanJson", ProjectPlanEnvelope.wrap(objectMapper, "{}", new ProjectRuntimeContext(USER_ID, 42L)));
        AgentPlanStep step = newStep("trusted-runtime", 1, List.of(), "[\"project_read_file\"]");
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(projectService.manifest(USER_ID, 42L)).thenReturn(new ProjectManifestResponse(42L, "runtime-manifest", List.of(
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, hash))));
        when(toolPolicyEngine.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(
                List.of("project_read_file"), 3, 1, "project"));
        String legacyResult = "{\"projectId\":42,\"relativePath\":\"paper/main.tex\",\"hash\":\"" + hash
                + "\",\"evidenceRefs\":[\"legacy-runtime-ref\"]}";
        EvidenceRef serverOwned = new EvidenceRef(trustedId, EvidenceSourceType.PROJECT, "PROJECT", "paper/main.tex",
                "tool:runtime-call", null, hash, "runtime-attested");
        AgentRuntimeResult result = new AgentRuntimeResult(true, "observed", List.of(
                ChatMessage.tool("runtime-call", legacyResult)), 1, null, List.of(), List.of(), null, null, null)
                .withTrustedEvidenceLedger(new EvidenceLedger(List.of(serverOwned)));
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class))).thenReturn(result);
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentPlanEvent> eventsCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, atLeast(1)).save(eventsCaptor.capture());
        AgentPlanEvent evidenceEvent = eventsCaptor.getAllValues().stream()
                .filter(value -> "step_project_evidence".equals(value.getEventType())).findFirst().orElseThrow();
        var evidence = objectMapper.readTree(evidenceEvent.getPayloadJson()).path("evidence");
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).path("id").asText())
                .startsWith("trusted-plan:42:" + PLAN_ID + ":" + step.getId() + ":1:")
                .isNotEqualTo(trustedId);
        assertThat(evidence.get(0).path("file").asText()).isEqualTo("paper/main.tex");
        assertThat(evidence.get(0).path("version").asText()).isEqualTo(hash);
    }

    @Test
    void executePlanTreatsPersistedEmptyAllowedToolsAsExplicitDenyAll() {
        AgentPlanStep step = newStep("step_1", 1, List.of());
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(success("tool-free synthesis"));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentRuntimeRequest> requestCaptor = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().toolPolicy().allowedTools()).isEmpty();
        assertThat(requestCaptor.getValue().toolPolicy().reason()).isEqualTo("plan_step_persisted_allowlist");
    }

    @Test
    void resumedPlanRestoresPersistedToolFailureStateIntoTheWorkerContext() {
        AgentPlanStep step = newStep("step_1", 1, List.of(), "[\"search_web\"]");
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        AgentPlanEvent previousFailure = new AgentPlanEvent(PLAN_ID, step.getId(), "step_tool_observation", """
                {"stepKey":"step_1","success":false,"trace":"step=1 tool=search_web args={query=wide} success=false error=input scope too large"}
                """);
        when(events.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenReturn(List.of(previousFailure));
        when(toolPolicyEngine.decide(any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new AgentToolPolicyEngine.Decision(List.of("search_web"), 8, 1, "shared_policy"));
        when(agentRuntimeService.run(any())).thenReturn(success("recovered with a narrower query"));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentRuntimeRequest> request = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService).run(request.capture());
        assertThat(request.getValue().history().get(0).content())
                .contains("Reusable observations from earlier attempts")
                .contains("input scope too large")
                .contains("Do not retry a deterministic failure with the same effective scope");
    }

    @Test
    void resumedPlanRestoresBoundedSuccessfulToolResultIntoTheWorkerContext() {
        AgentPlanStep step = newStep("step_1", 1, List.of(), "[\"project_search\"]");
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        AgentPlanEvent previousResult = new AgentPlanEvent(PLAN_ID, step.getId(), "step_tool_result", """
                {"stepKey":"step_1","toolName":"project_search","toolCallId":"call-1","result":"paper/main.tex:120 objective function"}
                """);
        when(events.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenReturn(List.of(previousResult));
        when(agentRuntimeService.run(any())).thenReturn(success("reused prior result"));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentRuntimeRequest> request = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService).run(request.capture());
        assertThat(request.getValue().history().get(0).content())
                .contains("REUSABLE RESULT tool=project_search")
                .contains("paper/main.tex:120 objective function")
                .contains("Do not repeat successful calls");
    }

    @Test
    void planCannotExposeHiddenInternalToolsOutsideTheSharedPolicy() {
        AgentPlanStep step = newStep("step_1", 1, List.of(), "[\"search_web\",\"literature_search_status\"]");
        when(toolPolicyEngine.decide(any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new AgentToolPolicyEngine.Decision(List.of("search_web"), 3, 1, "shared_policy"));
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(agentRuntimeService.run(any())).thenReturn(success("safe result"));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentRuntimeRequest> requestCaptor = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().toolPolicy().allowedTools()).containsExactly("search_web");
    }

    @Test
    void planAndReactUseTheSameResolvedPolicyWhenStepInheritsTools() {
        AgentPlanStep step = newStep("step_1", 1, List.of(), null);
        when(toolPolicyEngine.decide(any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new AgentToolPolicyEngine.Decision(List.of("search_web"), 6, 1, "shared_policy"));
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(agentRuntimeService.run(any())).thenReturn(success("safe result"));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<AgentRuntimeRequest> requestCaptor = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().toolPolicy().allowedTools()).containsExactly("search_web");
        assertThat(requestCaptor.getValue().toolPolicy().maxToolCalls()).isEqualTo(8);
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void plannerOnlyReceivesTheSameFilteredPolicyThatPlanStepsPersist() {
        when(toolPolicyEngine.decide(any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new AgentToolPolicyEngine.Decision(List.of("search_web"), 6, 1, "shared_policy"));
        when(planner.createPlan(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PlanningAgentPlanner.PlanSpec("summary", List.of(
                        new PlanningAgentPlanner.StepSpec("step_1", "Research", "Research safely", "RAG",
                                List.of(), List.of("search_web", "literature_search_status"), "done")), "{}"));
        when(plans.saveAndFlush(any(AgentPlan.class))).thenAnswer(invocation -> {
            AgentPlan saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", PLAN_ID);
            return saved;
        });
        when(steps.save(any(AgentPlanStep.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentPlanResponse response = service.createPlan(USER_ID, SESSION_ID,
                new CreateAgentPlanRequest("Research safely", false, null, false));

        ArgumentCaptor<List<String>> plannerTools = ArgumentCaptor.forClass(List.class);
        verify(planner).createPlan(any(), any(), any(), any(), any(), any(), plannerTools.capture());
        assertThat(plannerTools.getValue()).containsExactly("search_web");
        assertThat(response.steps()).singleElement().satisfies(step ->
                assertThat(step.allowedTools()).containsExactly("search_web"));
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void adapterPlanPolicyCannotExpandBeyondRuntimePolicyCeiling() {
        when(toolPolicyEngine.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(
                List.of("project_latex_outline", "project_code_symbols"), 12, 1, "project_policy"));
        when(planner.createPlan(any(), any(), any(), any(), any(), any(), any(),
                any(AgentOrchestrationRequirements.class)))
                .thenReturn(new PlanningAgentPlanner.PlanSpec("summary", List.of(
                        new PlanningAgentPlanner.StepSpec("step_1", "Inspect", "Inspect code", "ANALYSIS",
                                List.of(), List.of("project_latex_outline", "project_code_symbols"), "done")), "{}"));
        when(steps.save(any(AgentPlanStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AgentOrchestrationRequirements requirements = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.MATERIAL_CODE),
                List.of(AgentStrategyReasonCode.EXPLICIT_STRATEGY_SELECTED),
                List.of(new ResearchMaterialRequirement(ResearchMaterialKind.CODE,
                        List.of("project_code_symbols", "project_read_file"),
                        List.of("project_code_symbols"), true)));
        AgentRuntimeRequest runtimeRequest = new AgentRuntimeRequest(
                AgentStrategy.PLAN_EXECUTE, SESSION_ID, List.of(), USER_ID, "Inspect project code",
                "test", "model", null, null, 4, true, null, "key", "url", null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_code_symbols"), 2, 1, "runtime_ceiling"),
                2, 1, "trace-ceiling", null, null)
                .withProjectContext(new ProjectRuntimeContext(USER_ID, 42L))
                .withOrchestrationRequirements(requirements);

        AgentPlanResponse response = service.createPlanWithinAdapter(runtimeRequest);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> plannerTools = ArgumentCaptor.forClass(List.class);
        verify(planner).createPlan(any(), any(), any(), any(), any(), any(), plannerTools.capture(),
                org.mockito.ArgumentMatchers.eq(requirements));
        assertThat(plannerTools.getValue()).containsExactly("project_code_symbols");
        assertThat(response.steps()).singleElement().satisfies(step ->
                assertThat(step.allowedTools()).containsExactly("project_code_symbols"));
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void plannerFailureIsReturnedToPlanApiWithoutPersistingAPlan() {
        when(planner.createPlan(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(PlanningAgentPlanner.PlanSpec.failure(PlannerFailureCode.INVALID_PLAN, "malformed JSON"));

        assertThatThrownBy(() -> service.createPlan(USER_ID, SESSION_ID,
                new CreateAgentPlanRequest("Research safely", false, null, false)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("INVALID_PLAN");

        verify(plans, org.mockito.Mockito.never()).saveAndFlush(any(AgentPlan.class));
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void truncatedProjectPlanRetryPersistsExactlyOneReadOnlyPlan() {
        ChatModelProvider modelProvider = org.mockito.Mockito.mock(ChatModelProvider.class);
        when(modelProvider.chat(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(ChatMessage.assistant(
                        "{\"summary\":\"Inspect\",\"steps\":[{\"description\":\"truncated"), "length", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {
                          "summary": "Inspect Project",
                          "steps": [{
                            "id": "inspect",
                            "title": "Inspect source",
                            "description": "Read the authorized Project source.",
                            "type": "FILE_READ",
                            "dependencies": [],
                            "allowedTools": ["project_read_file", "write_file"],
                            "successCriteria": "The finding is grounded in the authorized source."
                          }]
                        }
                        """), "stop", null));
        PlanningAgentPlanner retryingPlanner = new PlanningAgentPlanner(modelProvider, objectMapper);
        PlanAgentService retryingService = new PlanAgentService(
                plans, steps, events, agentService, agentRuntimeService, null, retryingPlanner,
                stepVerifier, userSettingsService, skillsService, toolPolicyEngine, objectMapper, projectService);
        when(toolPolicyEngine.decideProject(any(), any())).thenReturn(
                new AgentToolPolicyEngine.Decision(List.of("project_read_file"), 6, 1, "project_read_only"));
        when(plans.saveAndFlush(any(AgentPlan.class))).thenAnswer(invocation -> {
            AgentPlan saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", PLAN_ID);
            return saved;
        });
        when(steps.save(any(AgentPlanStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AgentRuntimeRequest request = new AgentRuntimeRequest(
                AgentStrategy.PLAN_EXECUTE, SESSION_ID, List.of(), USER_ID, "Inspect Project source",
                "deepseek", "deepseek-v4-flash", null, null, 1, true, null, "test-api-key", null,
                null, AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 6, 1, "project_read_only"),
                null, null, "trace-plan-retry", null, null)
                .withProjectContext(new ProjectRuntimeContext(USER_ID, 42L));

        AgentPlanResponse response = retryingService.createPlanWithinAdapter(request);

        assertThat(response.id()).isEqualTo(PLAN_ID);
        assertThat(response.steps()).singleElement().satisfies(step ->
                assertThat(step.allowedTools()).containsExactly("project_read_file"));
        verify(modelProvider, times(2)).chat(any(ChatRequest.class));
        verify(plans, times(1)).saveAndFlush(any(AgentPlan.class));
        verify(steps, times(1)).save(any(AgentPlanStep.class));
        ArgumentCaptor<AgentPlanStep> persistedStep = ArgumentCaptor.forClass(AgentPlanStep.class);
        verify(steps).save(persistedStep.capture());
        assertThat(persistedStep.getValue().getAllowedToolsJson())
                .isEqualTo("[\"project_read_file\"]");
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void trustedPlanCreationIsCoordinatedBeforePlannerPersistence() {
        PlanAgentService coordinated = coordinatedService();
        AgentRuntimeResult runtimeResult = new AgentRuntimeResult(true, "created", List.of(), 0,
                null, List.of(), List.of(), null, null, null).withPlanId(PLAN_ID);
        when(runtimeCoordinator.coordinate(any())).thenReturn(new AgentCoordinationResult(
                new AgentCoordinationDecision(AgentStrategy.PLAN_EXECUTE, true, false, null, "trusted_plan_api"),
                runtimeResult,
                AgentRunProjection.fromRuntime(runtimeResult,
                        new com.yanban.core.agent.AgentRunIdentity("AGENT_PLAN", PLAN_ID.toString(),
                                USER_ID, SESSION_ID, null))));

        AgentPlanResponse response = coordinated.createPlan(USER_ID, SESSION_ID,
                new CreateAgentPlanRequest("Research safely", false, null, false));

        ArgumentCaptor<AgentCoordinationRequest> request = ArgumentCaptor.forClass(AgentCoordinationRequest.class);
        verify(runtimeCoordinator).coordinate(request.capture());
        assertThat(request.getValue().capability()).isEqualTo(AgentRequestCapability.TRUSTED_PLAN_API);
        assertThat(request.getValue().planOperation()).isEqualTo(PlanApiOperation.CREATE);
        assertThat(response.id()).isEqualTo(PLAN_ID);
        verify(planner, org.mockito.Mockito.never()).createPlan(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void adapterCreationPlannerFailureDoesNotPersistAPlan() {
        when(planner.createPlan(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(PlanningAgentPlanner.PlanSpec.failure(PlannerFailureCode.NO_STEPS, "no steps"));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(new AgentRuntimeRequest(
                AgentStrategy.PLAN_EXECUTE, SESSION_ID, List.of(), USER_ID, "Research safely", "test", "model", null,
                null, 1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, List.of(), 0, 0, "create-trace", null, null));

        assertThat(result.success()).isFalse();
        verify(plans, org.mockito.Mockito.never()).saveAndFlush(any(AgentPlan.class));
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void pausedExecutionPreservesConflictAndRecordsPausedAuditInsteadOfCoordinatorFailure() {
        PlanAgentService coordinated = coordinatedService();
        plan.markPaused();

        assertThatThrownBy(() -> coordinated.executePlan(USER_ID, PLAN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("paused");

        verify(runtimeCoordinator, org.mockito.Mockito.never()).coordinate(any());
        verify(events).save(any(AgentPlanEvent.class));
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void coordinatorReturnsPersistedDomainFailedPlanWithoutTurningItIntoHttp500() {
        PlanAgentService coordinated = coordinatedService();
        AgentRuntimeResult failed = new AgentRuntimeResult(false, "Plan failed", List.of(), 1,
                "step failed", List.of(), List.of(), null, null, null)
                .withPlanId(PLAN_ID)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.RUNTIME_FAILED,
                        "FAILURE", false, null);
        when(runtimeCoordinator.coordinate(any())).thenAnswer(invocation -> {
            plan.markFailed("step failed");
            return new AgentCoordinationResult(
                    new AgentCoordinationDecision(AgentStrategy.PLAN_EXECUTE, true, false, null, "trusted_plan_api"),
                    failed,
                    AgentRunProjection.fromRuntime(failed,
                            new com.yanban.core.agent.AgentRunIdentity("AGENT_PLAN", PLAN_ID.toString(),
                                    USER_ID, SESSION_ID, null)));
        });

        AgentPlanResponse response = coordinated.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.FAILED.name());
        assertThat(response.errorMessage()).isEqualTo("step failed");
    }

    private PlanAgentService coordinatedService() {
        return new PlanAgentService(plans, steps, events, agentService, agentRuntimeService, runtimeCoordinator,
                planner, stepVerifier, userSettingsService, skillsService, toolPolicyEngine, objectMapper);
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void persistedEmptyAllowlistDoesNotInheritSkillTools() {
        ResolvedToolPolicy inherited = new ResolvedToolPolicy(List.of("search_web"), 3, 1, "test");

        @SuppressWarnings("unchecked")
        List<String> resolved = ReflectionTestUtils.invokeMethod(
                service, "resolvePersistedAllowedTools", List.of(), inherited);

        assertThat(resolved).isEmpty();
    }

    @Test
    void executePlanRunsIndependentStepsInParallelBeforeDependentStep() {
        AgentPlanStep first = newStep("step_1", 1, List.of());
        AgentPlanStep second = newStep("step_2", 2, List.of());
        AgentPlanStep third = newStep("step_3", 3, List.of("step_1", "step_2"));
        List<AgentPlanStep> orderedSteps = List.of(first, second, third);
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(orderedSteps);

        CyclicBarrier firstBatchBarrier = new CyclicBarrier(2);
        AtomicInteger activeWorkers = new AtomicInteger();
        AtomicInteger maxActiveWorkers = new AtomicInteger();
        ConcurrentLinkedQueue<AgentRuntimeRequest> requests = new ConcurrentLinkedQueue<>();
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class))).thenAnswer(invocation -> {
            AgentRuntimeRequest request = invocation.getArgument(0);
            requests.add(request);
            String userMessage = request.userMessage();
            if (userMessage.contains("Description step_1") || userMessage.contains("Description step_2")) {
                int active = activeWorkers.incrementAndGet();
                maxActiveWorkers.updateAndGet(previous -> Math.max(previous, active));
                try {
                    firstBatchBarrier.await(3, TimeUnit.SECONDS);
                } finally {
                    activeWorkers.decrementAndGet();
                }
                String stepKey = userMessage.contains("Description step_1") ? "step_1" : "step_2";
                return success("result for " + stepKey);
            }
            return success("combined final result");
        });
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(response.steps()).extracting(AgentPlanStepResponse::status)
                .containsExactly(
                        AgentPlanStepStatus.COMPLETED.name(),
                        AgentPlanStepStatus.COMPLETED.name(),
                        AgentPlanStepStatus.COMPLETED.name()
                );
        assertThat(maxActiveWorkers.get()).isEqualTo(2);

        AgentRuntimeRequest dependentRequest = requests.stream()
                .filter(request -> request.userMessage().contains("Description step_3"))
                .findFirst()
                .orElseThrow();
        assertThat(dependentRequest.history().get(0).content())
                .contains("result for step_1")
                .contains("result for step_2");

        ArgumentCaptor<AgentPlanEvent> eventCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, atLeast(1)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(AgentPlanEvent::getEventType)
                .contains("step_batch_started", "step_batch_completed", "plan_completed");
        verify(agentRuntimeService, times(3)).run(any(AgentRuntimeRequest.class));
        verify(stepVerifier, times(3)).verify(any(PlanStepVerifier.VerificationRequest.class));
    }

    @Test
    void executePlanRetriesWithPreviousErrorInStepPrompt() {
        AgentPlanStep step = newStep("step_1", 1, List.of());
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(failure("missing source file"))
                .thenReturn(success("recovered result"));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(step.getStatus()).isEqualTo(AgentPlanStepStatus.COMPLETED.name());
        assertThat(step.getAttemptCount()).isEqualTo(2);
        assertThat(step.getResult()).isEqualTo("recovered result");

        ArgumentCaptor<AgentRuntimeRequest> requestCaptor = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService, times(2)).run(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues().get(1).history().get(0).content())
                .contains("Previous attempt error")
                .contains("missing source file");
        verify(stepVerifier).verify(any(PlanStepVerifier.VerificationRequest.class));
    }

    @Test
    void executePlanRetriesWhenVerificationRejectsCandidateResult() {
        AgentPlanStep step = newStep("step_1", 1, List.of());
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(successWithTrace("too vague", "step=1 tool=project_manifest args={} success=true"))
                .thenReturn(success("complete evidence-backed result"));
        when(stepVerifier.verify(any()))
                .thenReturn(PlanStepVerifier.VerificationResult.failed("missing reusable evidence"))
                .thenReturn(PlanStepVerifier.VerificationResult.passed("evidence is present"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(step.getStatus()).isEqualTo(AgentPlanStepStatus.COMPLETED.name());
        assertThat(step.getAttemptCount()).isEqualTo(2);
        assertThat(step.getResult()).isEqualTo("complete evidence-backed result");

        ArgumentCaptor<AgentRuntimeRequest> requestCaptor = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(agentRuntimeService, times(2)).run(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues().get(1).history().get(0).content())
                .contains("Previous attempt error")
                .contains("Step result did not satisfy success criteria")
                .contains("missing reusable evidence")
                .contains("Reusable observations from earlier attempts")
                .contains("project_manifest")
                .contains("Do not repeat successful calls");
        verify(stepVerifier, times(2)).verify(any(PlanStepVerifier.VerificationRequest.class));

        ArgumentCaptor<AgentPlanEvent> eventCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, times(7)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(AgentPlanEvent::getEventType)
                .contains("step_tool_observation", "step_verification_failed", "step_retry", "step_completed", "plan_completed");
    }

    @Test
    void executePlanPreservesResultAsDegradedWhenVerificationIsInconclusive() {
        AgentPlanStep step = newStep("step_1", 1, List.of());
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(success("usable result"));
        when(stepVerifier.verify(any()))
                .thenReturn(PlanStepVerifier.VerificationResult.inconclusive("verifier returned malformed JSON"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(step.getStatus()).isEqualTo(AgentPlanStepStatus.DEGRADED.name());
        assertThat(step.getAttemptCount()).isEqualTo(1);
        assertThat(step.getResult()).isEqualTo("usable result");
        verify(agentRuntimeService).run(any(AgentRuntimeRequest.class));
        verify(stepVerifier).verify(any(PlanStepVerifier.VerificationRequest.class));

        ArgumentCaptor<AgentPlanEvent> eventCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, times(5)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(AgentPlanEvent::getEventType)
                .contains("step_verification_inconclusive", "step_completed_unverified", "plan_completed");
    }

    @Test
    void executePlanCompletesControlledRuntimePartialWhenVerifierAcceptsPreservedResult() {
        AgentPlanStep step = newStep("step_1", 1, List.of());
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        AgentRuntimeResult partial = new AgentRuntimeResult(
                true,
                "Evidence was collected, but final synthesis timed out.",
                List.of(ChatMessage.assistant("Evidence was collected, but final synthesis timed out.")),
                3,
                null,
                List.of("step=1 tool=project_search args={} success=true"),
                List.of("Final synthesis unavailable after controlled stop: timeout"),
                null,
                null,
                null
        ).withRuntimeStopSignal(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED)
                .withCoordination(AgentStrategy.SINGLE_STEP_REACT, AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED,
                        "PARTIAL", true, AgentStrategy.SINGLE_STEP_REACT);
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class))).thenReturn(partial);
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("criteria covered"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(step.getStatus()).isEqualTo(AgentPlanStepStatus.COMPLETED.name());
        assertThat(step.getAttemptCount()).isEqualTo(1);
        assertThat(step.getResult()).contains("Evidence was collected");
        verify(agentRuntimeService).run(any(AgentRuntimeRequest.class));
        verify(stepVerifier).verify(any());

        ArgumentCaptor<AgentPlanEvent> eventCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, times(6)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(AgentPlanEvent::getEventType)
                .contains("step_tool_observation", "step_controlled_stop_ready_for_verification",
                        "step_completed_after_controlled_stop", "plan_completed");
    }

    @Test
    void executePlanKeepsControlledRuntimePartialDegradedWhenVerifierRejectsWithoutRetry() {
        AgentPlanStep step = newStep("step_1", 1, List.of());
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        AgentRuntimeResult partial = new AgentRuntimeResult(true, "Useful but incomplete result.",
                List.of(ChatMessage.assistant("Useful but incomplete result.")), 3, null,
                List.of("step=1 tool=project_search args={} success=true"), List.of(), null, null, null)
                .withRuntimeStopSignal(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED)
                .withCoordination(AgentStrategy.SINGLE_STEP_REACT, AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED,
                        "PARTIAL", true, AgentStrategy.SINGLE_STEP_REACT);
        when(agentRuntimeService.run(any())).thenReturn(partial);
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.failed("one criterion is missing"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(step.getStatus()).isEqualTo(AgentPlanStepStatus.DEGRADED.name());
        assertThat(step.getAttemptCount()).isEqualTo(1);
        verify(agentRuntimeService).run(any());
        verify(stepVerifier).verify(any());
    }

    @Test
    void executePlanRepairsFailedStepRewiresDependentsAndCompletes() {
        AgentPlanStep first = newStep("step_1", 1, List.of());
        AgentPlanStep second = newStep("step_2", 2, List.of("step_1"));
        List<AgentPlanStep> orderedSteps = new ArrayList<>(List.of(first, second));
        AtomicLong generatedIds = new AtomicLong(100);
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenAnswer(invocation -> orderedSteps.stream()
                .sorted(Comparator.comparing(AgentPlanStep::getSortOrder))
                .toList());
        when(steps.save(any(AgentPlanStep.class))).thenAnswer(invocation -> {
            AgentPlanStep saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", generatedIds.getAndIncrement());
            }
            if (!orderedSteps.contains(saved)) {
                orderedSteps.add(saved);
            }
            return saved;
        });
        when(planner.createRecoveryPlan(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PlanningAgentPlanner.PlanSpec(
                        "Repair failed step",
                        List.of(new PlanningAgentPlanner.StepSpec(
                                "recover_1",
                                "Recover missing evidence",
                                "Recover the missing evidence through an alternate route.",
                                "ANALYSIS",
                                List.of(),
                                List.of(),
                                "Recovered evidence is available for downstream steps."
                        )),
                        "{}"
                ));
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(failure("source unavailable"))
                .thenReturn(failure("still unavailable"))
                .thenReturn(success("recovered evidence"))
                .thenReturn(success("downstream result"));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        AgentPlanStepResponse repairStep = response.steps().stream()
                .filter(step -> step.stepKey().startsWith("repair_step_1"))
                .findFirst()
                .orElseThrow();
        AgentPlanStepResponse downstreamStep = response.steps().stream()
                .filter(step -> step.stepKey().equals("step_2"))
                .findFirst()
                .orElseThrow();

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(response.steps()).extracting(AgentPlanStepResponse::stepKey)
                .containsExactly("step_1", repairStep.stepKey(), "step_2");
        assertThat(response.steps()).extracting(AgentPlanStepResponse::status)
                .containsExactly(
                        AgentPlanStepStatus.SUPERSEDED.name(),
                        AgentPlanStepStatus.COMPLETED.name(),
                        AgentPlanStepStatus.COMPLETED.name()
                );
        assertThat(repairStep.result()).isEqualTo("recovered evidence");
        assertThat(downstreamStep.dependencies()).containsExactly(repairStep.stepKey());
        assertThat(downstreamStep.result()).isEqualTo("downstream result");
        verify(agentRuntimeService, times(4)).run(any(AgentRuntimeRequest.class));
        verify(stepVerifier, times(2)).verify(any(PlanStepVerifier.VerificationRequest.class));
    }

    @Test
    void executePlanRepairsStepAfterVerificationExhaustsAttempts() {
        AgentPlanStep first = newStep("step_1", 1, List.of());
        AgentPlanStep second = newStep("step_2", 2, List.of("step_1"));
        List<AgentPlanStep> orderedSteps = new ArrayList<>(List.of(first, second));
        AtomicLong generatedIds = new AtomicLong(200);
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenAnswer(invocation -> orderedSteps.stream()
                .sorted(Comparator.comparing(AgentPlanStep::getSortOrder))
                .toList());
        when(steps.save(any(AgentPlanStep.class))).thenAnswer(invocation -> {
            AgentPlanStep saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", generatedIds.getAndIncrement());
            }
            if (!orderedSteps.contains(saved)) {
                orderedSteps.add(saved);
            }
            return saved;
        });
        when(planner.createRecoveryPlan(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PlanningAgentPlanner.PlanSpec(
                        "Repair unverifiable step",
                        List.of(new PlanningAgentPlanner.StepSpec(
                                "recover_1",
                                "Produce verifiable evidence",
                                "Produce a verifiable result through an alternate route.",
                                "VERIFICATION",
                                List.of(),
                                List.of(),
                                "A concrete evidence-backed result is available."
                        )),
                        "{}"
                ));
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(success("vague first answer"))
                .thenReturn(success("vague second answer"))
                .thenReturn(success("verified recovery"))
                .thenReturn(success("downstream result"));
        when(stepVerifier.verify(any()))
                .thenReturn(PlanStepVerifier.VerificationResult.failed("missing concrete evidence"))
                .thenReturn(PlanStepVerifier.VerificationResult.failed("still missing concrete evidence"))
                .thenReturn(PlanStepVerifier.VerificationResult.passed("recovery is concrete"))
                .thenReturn(PlanStepVerifier.VerificationResult.passed("downstream is complete"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        AgentPlanStepResponse repairStep = response.steps().stream()
                .filter(step -> step.stepKey().startsWith("repair_step_1"))
                .findFirst()
                .orElseThrow();
        AgentPlanStepResponse downstreamStep = response.steps().stream()
                .filter(step -> step.stepKey().equals("step_2"))
                .findFirst()
                .orElseThrow();

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(response.steps()).extracting(AgentPlanStepResponse::status)
                .containsExactly(
                        AgentPlanStepStatus.SUPERSEDED.name(),
                        AgentPlanStepStatus.COMPLETED.name(),
                        AgentPlanStepStatus.COMPLETED.name()
                );
        assertThat(first.getErrorMessage()).contains("Superseded by recovery step").contains("still missing concrete evidence");
        assertThat(repairStep.result()).isEqualTo("verified recovery");
        assertThat(downstreamStep.dependencies()).containsExactly(repairStep.stepKey());
        verify(planner).createRecoveryPlan(any(), any(), any(), any(), any(), any(), any(), any());
        verify(agentRuntimeService, times(4)).run(any(AgentRuntimeRequest.class));
        verify(stepVerifier, times(4)).verify(any(PlanStepVerifier.VerificationRequest.class));
    }

    @Test
    void executePlanDegradesVerificationFailureWhenRepairIsUnavailable() {
        AgentPlanStep first = newStep("step_1", 1, List.of());
        AgentPlanStep second = newStep("step_2", 2, List.of("step_1"));
        List<AgentPlanStep> orderedSteps = List.of(first, second);
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(orderedSteps);
        when(planner.createRecoveryPlan(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(success("partial first answer"))
                .thenReturn(success("better but incomplete first answer"))
                .thenReturn(success("downstream result"));
        when(stepVerifier.verify(any()))
                .thenReturn(PlanStepVerifier.VerificationResult.failed("missing architecture details"))
                .thenReturn(PlanStepVerifier.VerificationResult.failed("still missing architecture details"))
                .thenReturn(PlanStepVerifier.VerificationResult.passed("downstream is complete"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(response.steps()).extracting(AgentPlanStepResponse::status)
                .containsExactly(AgentPlanStepStatus.DEGRADED.name(), AgentPlanStepStatus.COMPLETED.name());
        assertThat(first.getResult())
                .contains("better but incomplete first answer")
                .contains("[Degraded warning]")
                .contains("still missing architecture details");
        assertThat(first.getErrorMessage()).contains("Degraded after verification failure");
        assertThat(second.getResult()).isEqualTo("downstream result");
        verify(planner).createRecoveryPlan(any(), any(), any(), any(), any(), any(), any(), any());
        verify(agentRuntimeService, times(3)).run(any(AgentRuntimeRequest.class));
        verify(stepVerifier, times(3)).verify(any(PlanStepVerifier.VerificationRequest.class));

        ArgumentCaptor<AgentPlanEvent> eventCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, times(12)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(AgentPlanEvent::getEventType)
                .contains("step_repair_started", "step_repair_unavailable", "step_degraded", "plan_completed");
    }

    @Test
    void executePlanFailsStepAndSkipsTransitiveDependents() {
        AgentPlanStep first = newStep("step_1", 1, List.of());
        AgentPlanStep second = newStep("step_2", 2, List.of("step_1"));
        AgentPlanStep third = newStep("step_3", 3, List.of("step_2"));
        List<AgentPlanStep> orderedSteps = List.of(first, second, third);
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(orderedSteps);
        when(steps.save(any(AgentPlanStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(failure("first attempt failed"))
                .thenReturn(failure("second attempt failed"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.FAILED.name());
        assertThat(response.errorMessage()).contains("step_1").contains("second attempt failed");
        assertThat(response.steps()).extracting(AgentPlanStepResponse::status)
                .containsExactly(
                        AgentPlanStepStatus.FAILED.name(),
                        AgentPlanStepStatus.SKIPPED.name(),
                        AgentPlanStepStatus.SKIPPED.name()
                );
        assertThat(second.getErrorMessage()).contains("step_1");
        assertThat(third.getErrorMessage()).contains("step_2");
        verify(agentRuntimeService, times(2)).run(any(AgentRuntimeRequest.class));

        ArgumentCaptor<AgentPlanEvent> eventCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, times(9)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(AgentPlanEvent::getEventType)
                .contains("step_failed", "step_repair_started", "step_repair_unavailable", "step_skipped", "plan_failed");
    }

    private AgentRuntimeResult success(String content) {
        return new AgentRuntimeResult(
                true,
                content,
                List.of(ChatMessage.assistant(content)),
                1,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null
        );
    }

    private AgentRuntimeResult successWithTrace(String content, String trace) {
        return new AgentRuntimeResult(true, content, List.of(ChatMessage.assistant(content)), 1, null,
                List.of(trace), List.of(), null, null, null);
    }

    private AgentRuntimeResult failure(String error) {
        return new AgentRuntimeResult(
                false,
                null,
                List.of(ChatMessage.assistant("failed")),
                1,
                error,
                List.of(),
                List.of(error),
                null,
                null,
                null
        );
    }

    private AgentPlan newPlan() {
        AgentPlan value = new AgentPlan(
                SESSION_ID,
                USER_ID,
                "Complete the research task",
                "Research task plan",
                false,
                null,
                "{}"
        );
        ReflectionTestUtils.setField(value, "id", PLAN_ID);
        return value;
    }

    private AgentSession newSession() {
        AgentSession value = new AgentSession(
                USER_ID,
                "Plan test session",
                UserSettingsService.DEFAULT_PROVIDER,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                8,
                false
        );
        ReflectionTestUtils.setField(value, "id", SESSION_ID);
        return value;
    }

    private AgentPlanStep newStep(String key, int order, List<String> dependencies) {
        return newStep(key, order, dependencies, "[]");
    }

    private AgentPlanStep newStep(String key, int order, List<String> dependencies, String allowedToolsJson) {
        AgentPlanStep value = new AgentPlanStep(
                PLAN_ID,
                key,
                order,
                "Title " + key,
                "Description " + key,
                "ANALYSIS",
                writeJson(dependencies),
                allowedToolsJson,
                "The step has produced a usable result."
        );
        ReflectionTestUtils.setField(value, "id", (long) order);
        return value;
    }

    private SysUserSettings newSettings() {
        return new SysUserSettings(
                USER_ID,
                UserSettingsService.DEFAULT_PROVIDER,
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                new BigDecimal("0.70"),
                8,
                true
        );
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
