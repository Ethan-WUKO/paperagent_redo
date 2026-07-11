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
import java.math.BigDecimal;
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
                planner,
                stepVerifier,
                userSettingsService,
                skillsService,
                toolPolicyEngine,
                objectMapper
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
    }

    @Test
    void executePlanRunsStepsInDependencyOrderAndPassesDependencyResult() {
        AgentPlanStep first = newStep("step_1", 1, List.of());
        AgentPlanStep second = newStep("step_2", 2, List.of("step_1"));
        List<AgentPlanStep> orderedSteps = List.of(first, second);
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(orderedSteps);
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(success("analysis result"))
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
                .contains("analysis result");
        verify(stepVerifier, times(2)).verify(any(PlanStepVerifier.VerificationRequest.class));
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
        assertThat(requestCaptor.getValue().toolPolicy().maxToolCalls()).isEqualTo(3);
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
    void trustedPlanCreationIsCoordinatedBeforePlannerPersistence() {
        PlanAgentService coordinated = coordinatedService();
        AgentRuntimeResult runtimeResult = new AgentRuntimeResult(true, "created", List.of(), 0,
                null, List.of(), List.of(), null, null, null).withPlanId(PLAN_ID);
        when(runtimeCoordinator.coordinate(any())).thenReturn(new AgentCoordinationResult(
                new AgentCoordinationDecision(AgentStrategy.PLAN_EXECUTE, true, false, null, "trusted_plan_api"), runtimeResult));

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
                .thenReturn(success("too vague"))
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
                .contains("missing reusable evidence");
        verify(stepVerifier, times(2)).verify(any(PlanStepVerifier.VerificationRequest.class));

        ArgumentCaptor<AgentPlanEvent> eventCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, times(6)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(AgentPlanEvent::getEventType)
                .contains("step_verification_failed", "step_retry", "step_completed", "plan_completed");
    }

    @Test
    void executePlanCompletesWhenVerificationIsInconclusive() {
        AgentPlanStep step = newStep("step_1", 1, List.of());
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(agentRuntimeService.run(any(AgentRuntimeRequest.class)))
                .thenReturn(success("usable result"));
        when(stepVerifier.verify(any()))
                .thenReturn(PlanStepVerifier.VerificationResult.inconclusive("verifier returned malformed JSON"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(step.getStatus()).isEqualTo(AgentPlanStepStatus.COMPLETED.name());
        assertThat(step.getAttemptCount()).isEqualTo(1);
        assertThat(step.getResult()).isEqualTo("usable result");
        verify(agentRuntimeService).run(any(AgentRuntimeRequest.class));
        verify(stepVerifier).verify(any(PlanStepVerifier.VerificationRequest.class));

        ArgumentCaptor<AgentPlanEvent> eventCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, times(5)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(AgentPlanEvent::getEventType)
                .contains("step_verification_inconclusive", "step_completed", "plan_completed");
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
