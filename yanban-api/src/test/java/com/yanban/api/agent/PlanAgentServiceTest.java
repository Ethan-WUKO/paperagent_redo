package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.SkillsService;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanStatus;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepRepository;
import com.yanban.core.agent.AgentPlanStepStatus;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.harness.HarnessEngine;
import com.yanban.core.harness.HarnessRequest;
import com.yanban.core.harness.HarnessResult;
import com.yanban.core.model.ChatMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
import org.mockito.junit.jupiter.MockitoExtension;
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
    HarnessEngine harnessEngine;

    @Mock
    UserSettingsService userSettingsService;

    @Mock
    SkillsService skillsService;

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
                planner,
                stepVerifier,
                harnessEngine,
                userSettingsService,
                skillsService,
                objectMapper
        );
        plan = newPlan();
        session = newSession();

        when(plans.findByIdAndUserId(PLAN_ID, USER_ID)).thenReturn(Optional.of(plan));
        when(agentService.getOwnedSession(USER_ID, SESSION_ID)).thenReturn(session);
        when(userSettingsService.resolveModelEndpoint(anyLong(), any(), any()))
                .thenReturn(new UserSettingsService.ModelEndpoint(UserSettingsService.DEFAULT_PROVIDER, UserSettingsService.DEFAULT_DEEPSEEK_MODEL, null, "test-api-key"));
        when(plans.saveAndFlush(any(AgentPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(steps.saveAndFlush(any(AgentPlanStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(events.save(any(AgentPlanEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void executePlanRunsStepsInDependencyOrderAndPassesDependencyResult() {
        AgentPlanStep first = newStep("step_1", 1, List.of());
        AgentPlanStep second = newStep("step_2", 2, List.of("step_1"));
        List<AgentPlanStep> orderedSteps = List.of(first, second);
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(orderedSteps);
        when(harnessEngine.run(any(HarnessRequest.class)))
                .thenReturn(HarnessResult.success("analysis result", List.of(ChatMessage.assistant("analysis result")), 1))
                .thenReturn(HarnessResult.success("final result", List.of(ChatMessage.assistant("final result")), 1));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(response.steps()).extracting(AgentPlanStepResponse::status)
                .containsExactly(AgentPlanStepStatus.COMPLETED.name(), AgentPlanStepStatus.COMPLETED.name());
        assertThat(first.getResult()).isEqualTo("analysis result");
        assertThat(second.getResult()).isEqualTo("final result");

        ArgumentCaptor<HarnessRequest> requestCaptor = ArgumentCaptor.forClass(HarnessRequest.class);
        verify(harnessEngine, times(2)).run(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues().get(1).history().get(0).content())
                .contains("analysis result");
        verify(stepVerifier, times(2)).verify(any(PlanStepVerifier.VerificationRequest.class));
    }

    @Test
    void executePlanTreatsPersistedEmptyAllowedToolsAsUnrestrictedRuntimeTools() {
        AgentPlanStep step = newStep("step_1", 1, List.of());
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(harnessEngine.run(any(HarnessRequest.class)))
                .thenReturn(HarnessResult.success("tool-free synthesis", List.of(ChatMessage.assistant("tool-free synthesis")), 1));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        service.executePlan(USER_ID, PLAN_ID);

        ArgumentCaptor<HarnessRequest> requestCaptor = ArgumentCaptor.forClass(HarnessRequest.class);
        verify(harnessEngine).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().allowedToolNames()).isNull();
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
        ConcurrentLinkedQueue<HarnessRequest> requests = new ConcurrentLinkedQueue<>();
        when(harnessEngine.run(any(HarnessRequest.class))).thenAnswer(invocation -> {
            HarnessRequest request = invocation.getArgument(0);
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
                return HarnessResult.success("result for " + stepKey, List.of(ChatMessage.assistant("result for " + stepKey)), 1);
            }
            return HarnessResult.success("combined final result", List.of(ChatMessage.assistant("combined final result")), 1);
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

        HarnessRequest dependentRequest = requests.stream()
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
        verify(harnessEngine, times(3)).run(any(HarnessRequest.class));
        verify(stepVerifier, times(3)).verify(any(PlanStepVerifier.VerificationRequest.class));
    }

    @Test
    void executePlanRetriesWithPreviousErrorInStepPrompt() {
        AgentPlanStep step = newStep("step_1", 1, List.of());
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(harnessEngine.run(any(HarnessRequest.class)))
                .thenReturn(HarnessResult.failure("missing source file", List.of(ChatMessage.assistant("failed")), 1))
                .thenReturn(HarnessResult.success("recovered result", List.of(ChatMessage.assistant("recovered result")), 1));
        when(stepVerifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(step.getStatus()).isEqualTo(AgentPlanStepStatus.COMPLETED.name());
        assertThat(step.getAttemptCount()).isEqualTo(2);
        assertThat(step.getResult()).isEqualTo("recovered result");

        ArgumentCaptor<HarnessRequest> requestCaptor = ArgumentCaptor.forClass(HarnessRequest.class);
        verify(harnessEngine, times(2)).run(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues().get(1).history().get(0).content())
                .contains("Previous attempt error")
                .contains("missing source file");
        verify(stepVerifier).verify(any(PlanStepVerifier.VerificationRequest.class));
    }

    @Test
    void executePlanRetriesWhenVerificationRejectsCandidateResult() {
        AgentPlanStep step = newStep("step_1", 1, List.of());
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenReturn(List.of(step));
        when(harnessEngine.run(any(HarnessRequest.class)))
                .thenReturn(HarnessResult.success("too vague", List.of(ChatMessage.assistant("too vague")), 1))
                .thenReturn(HarnessResult.success("complete evidence-backed result", List.of(ChatMessage.assistant("complete evidence-backed result")), 1));
        when(stepVerifier.verify(any()))
                .thenReturn(PlanStepVerifier.VerificationResult.failed("missing reusable evidence"))
                .thenReturn(PlanStepVerifier.VerificationResult.passed("evidence is present"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(step.getStatus()).isEqualTo(AgentPlanStepStatus.COMPLETED.name());
        assertThat(step.getAttemptCount()).isEqualTo(2);
        assertThat(step.getResult()).isEqualTo("complete evidence-backed result");

        ArgumentCaptor<HarnessRequest> requestCaptor = ArgumentCaptor.forClass(HarnessRequest.class);
        verify(harnessEngine, times(2)).run(requestCaptor.capture());
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
        when(harnessEngine.run(any(HarnessRequest.class)))
                .thenReturn(HarnessResult.success("usable result", List.of(ChatMessage.assistant("usable result")), 1));
        when(stepVerifier.verify(any()))
                .thenReturn(PlanStepVerifier.VerificationResult.inconclusive("verifier returned malformed JSON"));

        AgentPlanResponse response = service.executePlan(USER_ID, PLAN_ID);

        assertThat(response.status()).isEqualTo(AgentPlanStatus.COMPLETED.name());
        assertThat(step.getStatus()).isEqualTo(AgentPlanStepStatus.COMPLETED.name());
        assertThat(step.getAttemptCount()).isEqualTo(1);
        assertThat(step.getResult()).isEqualTo("usable result");
        verify(harnessEngine).run(any(HarnessRequest.class));
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
        when(harnessEngine.run(any(HarnessRequest.class)))
                .thenReturn(HarnessResult.failure("source unavailable", List.of(ChatMessage.assistant("failed")), 1))
                .thenReturn(HarnessResult.failure("still unavailable", List.of(ChatMessage.assistant("failed")), 1))
                .thenReturn(HarnessResult.success("recovered evidence", List.of(ChatMessage.assistant("recovered evidence")), 1))
                .thenReturn(HarnessResult.success("downstream result", List.of(ChatMessage.assistant("downstream result")), 1));
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
        verify(harnessEngine, times(4)).run(any(HarnessRequest.class));
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
        when(harnessEngine.run(any(HarnessRequest.class)))
                .thenReturn(HarnessResult.success("vague first answer", List.of(ChatMessage.assistant("vague first answer")), 1))
                .thenReturn(HarnessResult.success("vague second answer", List.of(ChatMessage.assistant("vague second answer")), 1))
                .thenReturn(HarnessResult.success("verified recovery", List.of(ChatMessage.assistant("verified recovery")), 1))
                .thenReturn(HarnessResult.success("downstream result", List.of(ChatMessage.assistant("downstream result")), 1));
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
        verify(harnessEngine, times(4)).run(any(HarnessRequest.class));
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
        when(harnessEngine.run(any(HarnessRequest.class)))
                .thenReturn(HarnessResult.success("partial first answer", List.of(ChatMessage.assistant("partial first answer")), 1))
                .thenReturn(HarnessResult.success("better but incomplete first answer", List.of(ChatMessage.assistant("better but incomplete first answer")), 1))
                .thenReturn(HarnessResult.success("downstream result", List.of(ChatMessage.assistant("downstream result")), 1));
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
        verify(harnessEngine, times(3)).run(any(HarnessRequest.class));
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
        when(harnessEngine.run(any(HarnessRequest.class)))
                .thenReturn(HarnessResult.failure("first attempt failed", List.of(ChatMessage.assistant("failed")), 1))
                .thenReturn(HarnessResult.failure("second attempt failed", List.of(ChatMessage.assistant("failed")), 1));

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
        verify(harnessEngine, times(2)).run(any(HarnessRequest.class));

        ArgumentCaptor<AgentPlanEvent> eventCaptor = ArgumentCaptor.forClass(AgentPlanEvent.class);
        verify(events, times(9)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(AgentPlanEvent::getEventType)
                .contains("step_failed", "step_repair_started", "step_repair_unavailable", "step_skipped", "plan_failed");
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
        AgentPlanStep value = new AgentPlanStep(
                PLAN_ID,
                key,
                order,
                "Title " + key,
                "Description " + key,
                "ANALYSIS",
                writeJson(dependencies),
                "[]",
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
