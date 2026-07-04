package com.yanban.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.ResolvedSkill;
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
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlanAgentService {

    private static final Logger log = LoggerFactory.getLogger(PlanAgentService.class);
    private static final int MAX_STEP_ATTEMPTS = 2;
    private static final int MAX_REPAIR_STEPS = 3;
    private static final int MAX_PARALLEL_STEPS = 3;
    private static final int MAX_HARNESS_STEPS_PER_PLAN_STEP = 3;
    private static final int MAX_TOOL_CALLS_PER_PLAN_STEP = 3;
    private static final int MAX_DUPLICATE_TOOL_CALLS_PER_PLAN_STEP = 1;
    private static final int PLAN_EXECUTOR_THREADS = 2;
    private static final int PLAN_BUDGET_SECONDS = 240;
    private static final String REPAIR_STEP_PREFIX = "repair_";

    private final AgentPlanRepository plans;
    private final AgentPlanStepRepository steps;
    private final AgentPlanEventRepository events;
    private final AgentService agentService;
    private final PlanningAgentPlanner planner;
    private final PlanStepVerifier stepVerifier;
    private final HarnessEngine harnessEngine;
    private final UserSettingsService userSettingsService;
    private final SkillsService skillsService;
    private final ObjectMapper objectMapper;
    private final ExecutorService planExecutor = Executors.newFixedThreadPool(PLAN_EXECUTOR_THREADS);

    public PlanAgentService(AgentPlanRepository plans,
                            AgentPlanStepRepository steps,
                            AgentPlanEventRepository events,
                            AgentService agentService,
                            PlanningAgentPlanner planner,
                            PlanStepVerifier stepVerifier,
                            HarnessEngine harnessEngine,
                            UserSettingsService userSettingsService,
                            SkillsService skillsService,
                            ObjectMapper objectMapper) {
        this.plans = plans;
        this.steps = steps;
        this.events = events;
        this.agentService = agentService;
        this.planner = planner;
        this.stepVerifier = stepVerifier;
        this.harnessEngine = harnessEngine;
        this.userSettingsService = userSettingsService;
        this.skillsService = skillsService;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdownPlanExecutor() {
        planExecutor.shutdownNow();
    }

    @Transactional
    public AgentPlanResponse createPlan(Long userId, Long sessionId, CreateAgentPlanRequest request) {
        AgentSession session = agentService.getOwnedSession(userId, sessionId);
        boolean ragDisabled = request.ragDisabled() != null
                ? request.ragDisabled()
                : Boolean.TRUE.equals(session.getRagDisabled());
        ResolvedSkill skill = resolveSkill(userId, request.skillId());
        UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                userId, session.getModelProviderSnapshot(), session.getModelSnapshot());
        PlanningAgentPlanner.PlanSpec spec = planner.createPlan(
                request.content(),
                endpoint.providerKey(),
                endpoint.modelName(),
                endpoint.apiKey(),
                endpoint.apiUrl(),
                skill == null ? null : skill.prompt(),
                skill == null ? null : List.copyOf(skill.allowedTools())
        );
        validatePlanSpec(spec);

        AgentPlan plan = new AgentPlan(
                sessionId,
                userId,
                request.content().trim(),
                spec.summary(),
                ragDisabled,
                skill == null ? null : skill.id(),
                spec.rawJson()
        );
        plan = plans.saveAndFlush(plan);

        List<AgentPlanStep> savedSteps = new ArrayList<>();
        int order = 1;
        for (PlanningAgentPlanner.StepSpec step : spec.steps()) {
            List<String> allowedTools = resolvePersistedAllowedTools(step.allowedTools(), skill);
            savedSteps.add(steps.save(new AgentPlanStep(
                    plan.getId(),
                    step.id(),
                    order++,
                    step.title(),
                    step.description(),
                    step.type(),
                    writeJson(step.dependencies()),
                    writeJson(allowedTools),
                    step.successCriteria()
            )));
        }
        steps.flush();
        recordEvent(plan.getId(), null, "plan_created", Map.of(
                "summary", plan.getSummary(),
                "stepCount", savedSteps.size()
        ));

        if (Boolean.TRUE.equals(request.autoExecute())) {
            return executePlanAsync(userId, plan.getId());
        }
        return toResponse(plan, savedSteps);
    }

    @Transactional(readOnly = true)
    public List<AgentPlanResponse> listSessionPlans(Long userId, Long sessionId) {
        agentService.getOwnedSession(userId, sessionId);
        return plans.findBySessionIdAndUserIdOrderByCreatedAtDesc(sessionId, userId).stream()
                .map(plan -> toResponse(plan, steps.findByPlanIdOrderBySortOrderAsc(plan.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentPlanResponse getPlan(Long userId, Long planId) {
        AgentPlan plan = getOwnedPlan(userId, planId);
        return toResponse(plan, steps.findByPlanIdOrderBySortOrderAsc(plan.getId()));
    }

    @Transactional(readOnly = true)
    public List<AgentPlanEventResponse> listEvents(Long userId, Long planId) {
        AgentPlan plan = getOwnedPlan(userId, planId);
        return events.findByPlanIdOrderByCreatedAtAsc(plan.getId()).stream()
                .map(AgentPlanEventResponse::from)
                .toList();
    }

    public AgentPlanResponse executePlanAsync(Long userId, Long planId) {
        AgentPlan plan = getOwnedPlan(userId, planId);
        if (plan.terminal() || AgentPlanStatus.RUNNING.name().equals(plan.getStatus())) {
            return toResponse(plan, steps.findByPlanIdOrderBySortOrderAsc(plan.getId()));
        }
        if (AgentPlanStatus.PAUSED.name().equals(plan.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan is paused and cannot be executed.");
        }

        String traceId = newPlanTraceId(planId);
        recordEvent(plan.getId(), null, "plan_queued", Map.of("traceId", traceId, "mode", "async"));
        plan.markRunning();
        plans.saveAndFlush(plan);
        planExecutor.submit(() -> runPlanAsyncWorker(userId, planId, traceId));
        return toResponse(plan, steps.findByPlanIdOrderBySortOrderAsc(plan.getId()));
    }

    public AgentPlanResponse retryPlan(Long userId, Long planId) {
        AgentPlan plan = getOwnedPlan(userId, planId);
        if (AgentPlanStatus.RUNNING.name().equals(plan.getStatus())) {
            return toResponse(plan, steps.findByPlanIdOrderBySortOrderAsc(plan.getId()));
        }
        if (!AgentPlanStatus.FAILED.name().equals(plan.getStatus())
                && !AgentPlanStatus.CANCELLED.name().equals(plan.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only FAILED or CANCELLED plans can be retried.");
        }

        List<AgentPlanStep> planSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
        long retryable = 0;
        for (AgentPlanStep step : planSteps) {
            if (!planStepSatisfied(step)) {
                retryable++;
                step.resetForRetry();
                steps.save(step);
            }
        }
        steps.flush();
        plan.resetForRetry();
        plans.saveAndFlush(plan);
        recordEvent(plan.getId(), null, "plan_retry_queued", Map.of("retryableStepCount", retryable));
        return executePlanAsync(userId, planId);
    }

    private void runPlanAsyncWorker(Long userId, Long planId, String traceId) {
        String previousTraceId = MDC.get("traceId");
        MDC.put("traceId", traceId);
        try {
            executePlan(userId, planId, traceId);
        } catch (Exception ex) {
            log.warn("Async plan execution failed planId={} traceId={}", planId, traceId, ex);
            try {
                AgentPlan plan = plans.findByIdAndUserId(planId, userId).orElse(null);
                if (plan != null && !plan.terminal()) {
                    plan.markFailed("Async plan execution crashed: "
                            + abbreviate(blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 1200));
                    plans.saveAndFlush(plan);
                    recordEvent(plan.getId(), null, "plan_failed", Map.of(
                            "traceId", traceId,
                            "error", plan.getErrorMessage()
                    ));
                }
            } catch (Exception markEx) {
                log.warn("Failed to mark async plan execution failure planId={}", planId, markEx);
            }
        } finally {
            restoreTraceId(previousTraceId);
        }
    }

    public AgentPlanResponse executePlan(Long userId, Long planId) {
        return executePlan(userId, planId, newPlanTraceId(planId));
    }

    private AgentPlanResponse executePlan(Long userId, Long planId, String traceId) {
        String previousTraceId = MDC.get("traceId");
        MDC.put("traceId", traceId);
        try {
            AgentPlan plan = getOwnedPlan(userId, planId);
            if (plan.terminal()) {
                return toResponse(plan, steps.findByPlanIdOrderBySortOrderAsc(plan.getId()));
            }
            if (AgentPlanStatus.PAUSED.name().equals(plan.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan is paused and cannot be executed.");
            }

            AgentSession session = agentService.getOwnedSession(userId, plan.getSessionId());
            ResolvedSkill skill = resolveSkill(userId, plan.getSkillId());
            plan.markRunning();
            plans.saveAndFlush(plan);
            LocalDateTime deadlineAt = LocalDateTime.now().plusSeconds(PLAN_BUDGET_SECONDS);
            recordEvent(plan.getId(), null, "plan_started", Map.of(
                    "goal", plan.getGoal(),
                    "traceId", traceId,
                    "budgetSeconds", PLAN_BUDGET_SECONDS
            ));

            List<AgentPlanStep> allSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
            while (true) {
                if (planCancelled(plan.getId(), userId)) {
                    AgentPlan cancelled = plans.findByIdAndUserId(plan.getId(), userId).orElse(plan);
                    allSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
                    persistConversationSummary(userId, session.getId(), cancelled, allSteps);
                    return toResponse(cancelled, allSteps);
                }
                if (deadlineExceeded(deadlineAt)) {
                    markPlanBudgetExceeded(plan, allSteps, session.getId(), userId, traceId);
                    allSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
                    return toResponse(plan, allSteps);
                }

                AgentPlanStep failedStep = firstFailedStep(allSteps);
                if (failedStep != null) {
                    if (recoverFailedStep(plan, session, allSteps, failedStep, skill)) {
                        allSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
                        continue;
                    }
                    String failedError = failedStep.getErrorMessage();
                    failedStep.markFailed(failedError, failedStep.getResult());
                    steps.saveAndFlush(failedStep);
                    markBlockedSteps(allSteps, failedStep);
                    allSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
                    plan.markFailed("Step " + failedStep.getStepKey() + " failed: " + failedStep.getErrorMessage());
                    plans.saveAndFlush(plan);
                    recordEvent(plan.getId(), failedStep.getId(), "plan_failed", Map.of(
                            "traceId", traceId,
                            "error", plan.getErrorMessage()
                    ));
                    persistConversationSummary(userId, session.getId(), plan, allSteps);
                    return toResponse(plan, allSteps);
                }

                List<AgentPlanStep> executable = executableSteps(allSteps);
                if (executable.isEmpty()) {
                    break;
                }
                List<AgentPlanStep> batch = executable.stream()
                        .limit(MAX_PARALLEL_STEPS)
                        .toList();
                executeStepBatch(plan, session, allSteps, batch, skill, deadlineAt, traceId);
                allSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
            }

            boolean allCompleted = allSteps.stream().allMatch(this::planStepSatisfied);
            if (allCompleted) {
                plan.markCompleted();
                plans.saveAndFlush(plan);
                recordEvent(plan.getId(), null, "plan_completed", Map.of(
                        "traceId", traceId,
                        "summary", buildFinalSummary(plan, allSteps)
                ));
            } else {
                plan.markFailed("Plan cannot continue because dependencies or steps remain incomplete.");
                plans.saveAndFlush(plan);
                recordEvent(plan.getId(), null, "plan_final_verification_failed", Map.of(
                        "traceId", traceId,
                        "error", plan.getErrorMessage()
                ));
                recordEvent(plan.getId(), null, "plan_stalled", Map.of(
                        "traceId", traceId,
                        "error", plan.getErrorMessage()
                ));
            }
            persistConversationSummary(userId, session.getId(), plan, allSteps);
            return toResponse(plan, allSteps);
        } finally {
            restoreTraceId(previousTraceId);
        }
    }

    @Transactional
    public AgentPlanResponse cancelPlan(Long userId, Long planId) {
        AgentPlan plan = getOwnedPlan(userId, planId);
        if (!plan.terminal()) {
            plan.markCancelled("User cancelled plan.");
            plans.saveAndFlush(plan);
            recordEvent(plan.getId(), null, "plan_cancelled", Map.of("reason", "User cancelled plan."));
        }
        return toResponse(plan, steps.findByPlanIdOrderBySortOrderAsc(plan.getId()));
    }

    private boolean planStepSatisfied(AgentPlanStep step) {
        return AgentPlanStepStatus.COMPLETED.name().equals(step.getStatus())
                || AgentPlanStepStatus.DEGRADED.name().equals(step.getStatus())
                || AgentPlanStepStatus.SUPERSEDED.name().equals(step.getStatus());
    }

    private AgentPlanStep firstFailedStep(List<AgentPlanStep> allSteps) {
        return allSteps.stream()
                .filter(step -> AgentPlanStepStatus.FAILED.name().equals(step.getStatus()))
                .min(Comparator.comparing(AgentPlanStep::getSortOrder))
                .orElse(null);
    }

    private boolean recoverFailedStep(AgentPlan plan,
                                      AgentSession session,
                                      List<AgentPlanStep> allSteps,
                                      AgentPlanStep failedStep,
                                      ResolvedSkill skill) {
        String failedError = failedStep.getErrorMessage();
        failedStep.markRepairing(failedError);
        steps.saveAndFlush(failedStep);
        recordEvent(plan.getId(), failedStep.getId(), "step_repair_started", Map.of(
                "stepKey", failedStep.getStepKey(),
                "error", blankToDefault(failedError, "Step failed.")
        ));
        if (tryRepairFailedStep(plan, session, allSteps, failedStep, skill)) {
            return true;
        }
        return tryDegradeFailedStep(plan, failedStep, failedError);
    }

    private void executeStep(AgentPlan plan,
                             AgentSession session,
                             List<AgentPlanStep> allSteps,
                             AgentPlanStep step,
                             ResolvedSkill skill,
                             LocalDateTime deadlineAt,
                             String traceId) {
        recordEvent(plan.getId(), step.getId(), "step_started", Map.of(
                "stepKey", step.getStepKey(),
                "title", blankToDefault(step.getTitle(), ""),
                "traceId", traceId
        ));
        String previousError = step.getErrorMessage();
        for (int attempt = Math.max(0, step.getAttemptCount()); attempt < MAX_STEP_ATTEMPTS; attempt++) {
            if (deadlineExceeded(deadlineAt)) {
                step.markFailed("Plan execution budget exceeded before this step completed.");
                recordEvent(plan.getId(), step.getId(), "step_failed", Map.of(
                        "stepKey", step.getStepKey(),
                        "error", step.getErrorMessage(),
                        "traceId", traceId
                ));
                return;
            }
            step.markRunning();
            steps.saveAndFlush(step);
            UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                    plan.getUserId(), session.getModelProviderSnapshot(), session.getModelSnapshot());
            HarnessRequest harnessRequest = new HarnessRequest(
                    List.of(ChatMessage.system(buildStepSystemPrompt(plan, allSteps, step, previousError))),
                    plan.getUserId(),
                    buildStepUserMessage(step),
                    endpoint.providerKey(),
                    endpoint.modelName(),
                    null,
                    null,
                    maxHarnessStepsForPlanStep(session),
                    Boolean.TRUE.equals(plan.getRagDisabled()),
                    endpoint.apiKey(),
                    endpoint.apiUrl(),
                    skill == null ? null : skill.prompt(),
                    resolveRuntimeAllowedTools(step, skill),
                    MAX_TOOL_CALLS_PER_PLAN_STEP,
                    MAX_DUPLICATE_TOOL_CALLS_PER_PLAN_STEP,
                    deadlineAt,
                    traceId
            );
            HarnessResult result;
            try {
                result = harnessEngine.run(harnessRequest);
            } catch (Exception ex) {
                String error = "Step worker execution crashed: "
                        + abbreviate(blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 1200);
                log.warn("Plan step worker crashed planId={} stepKey={} traceId={}",
                        plan.getId(), step.getStepKey(), traceId, ex);
                result = HarnessResult.failure(error, List.of(), attempt + 1);
            }
            if (result.success()) {
                String content = StringUtils.hasText(result.assistantContent())
                        ? result.assistantContent()
                        : "Step completed.";
                PlanStepVerifier.VerificationResult verification = stepVerifier.verify(new PlanStepVerifier.VerificationRequest(
                        plan,
                        session,
                        step,
                        allSteps,
                        content,
                        endpoint.apiKey(),
                        endpoint.apiUrl(),
                        traceId
                ));
                if (verification == null) {
                    verification = PlanStepVerifier.VerificationResult.inconclusive("Verifier returned no decision.");
                }
                if (!verification.conclusive()) {
                    recordEvent(plan.getId(), step.getId(), "step_verification_inconclusive", Map.of(
                            "stepKey", step.getStepKey(),
                            "reason", blankToDefault(verification.reason(), "Verifier could not make a reliable decision."),
                            "candidateResult", abbreviate(content, 1200),
                            "traceId", traceId
                    ));
                }
                if (!verification.passed()) {
                    String error = buildVerificationFailureMessage(verification);
                    previousError = error;
                    recordEvent(plan.getId(), step.getId(), "step_verification_failed", Map.of(
                            "stepKey", step.getStepKey(),
                            "error", error,
                            "candidateResult", abbreviate(content, 1200),
                            "traceId", traceId
                    ));
                    if (attempt + 1 >= MAX_STEP_ATTEMPTS) {
                        step.markFailed(error, content);
                        recordEvent(plan.getId(), step.getId(), "step_failed", Map.of(
                                "stepKey", step.getStepKey(),
                                "error", error,
                                "traceId", traceId
                        ));
                        return;
                    }
                    recordEvent(plan.getId(), step.getId(), "step_retry", Map.of(
                            "stepKey", step.getStepKey(),
                            "attempt", step.getAttemptCount(),
                            "error", error,
                            "traceId", traceId
                    ));
                    continue;
                }
                step.markCompleted(content);
                recordEvent(plan.getId(), step.getId(), "step_completed", Map.of(
                        "stepKey", step.getStepKey(),
                        "steps", result.steps(),
                        "result", abbreviate(content, 1200),
                        "traceId", traceId
                ));
                return;
            }

            String error = StringUtils.hasText(result.errorMessage()) ? result.errorMessage() : "Step execution failed.";
            previousError = error;
            recordHarnessGuardrailEvent(plan, step, error, traceId);
            if (attempt + 1 >= MAX_STEP_ATTEMPTS) {
                step.markFailed(error);
                recordEvent(plan.getId(), step.getId(), "step_failed", Map.of(
                        "stepKey", step.getStepKey(),
                        "error", error,
                        "traceId", traceId
                ));
                return;
            }
            recordEvent(plan.getId(), step.getId(), "step_retry", Map.of(
                    "stepKey", step.getStepKey(),
                    "attempt", step.getAttemptCount(),
                    "error", error,
                    "traceId", traceId
            ));
        }
    }

    private void executeStepBatch(AgentPlan plan,
                                  AgentSession session,
                                  List<AgentPlanStep> allSteps,
                                  List<AgentPlanStep> batch,
                                  ResolvedSkill skill,
                                  LocalDateTime deadlineAt,
                                  String traceId) {
        if (batch.isEmpty()) {
            return;
        }
        if (batch.size() == 1) {
            executeStepSafely(plan, session, allSteps, batch.get(0), skill, deadlineAt, traceId);
            return;
        }

        recordEvent(plan.getId(), null, "step_batch_started", Map.of(
                "stepKeys", batch.stream().map(AgentPlanStep::getStepKey).toList(),
                "parallelism", Math.min(batch.size(), MAX_PARALLEL_STEPS),
                "traceId", traceId
        ));
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(batch.size(), MAX_PARALLEL_STEPS));
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (AgentPlanStep step : batch) {
                futures.add(executor.submit(withMdc(parentMdc,
                        () -> executeStepSafely(plan, session, allSteps, step, skill, deadlineAt, traceId))));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    markInterruptedBatch(plan, batch, traceId);
                    executor.shutdownNow();
                    break;
                } catch (Exception ex) {
                    log.warn("Plan step worker future failed planId={} traceId={}", plan.getId(), traceId, ex);
                }
            }
        } finally {
            executor.shutdown();
        }
        recordEvent(plan.getId(), null, "step_batch_completed", Map.of(
                "stepKeys", batch.stream().map(AgentPlanStep::getStepKey).toList(),
                "traceId", traceId
        ));
    }

    private Runnable withMdc(Map<String, String> parentMdc, Runnable delegate) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (parentMdc == null || parentMdc.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(parentMdc);
            }
            try {
                delegate.run();
            } finally {
                if (previous == null || previous.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }

    private void executeStepSafely(AgentPlan plan,
                                   AgentSession session,
                                   List<AgentPlanStep> allSteps,
                                   AgentPlanStep step,
                                   ResolvedSkill skill,
                                   LocalDateTime deadlineAt,
                                   String traceId) {
        try {
            executeStep(plan, session, allSteps, step, skill, deadlineAt, traceId);
            steps.saveAndFlush(step);
        } catch (Exception ex) {
            String error = "Step worker failed unexpectedly: "
                    + abbreviate(blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 1200);
            log.warn("Plan step worker failed unexpectedly planId={} stepKey={} traceId={}",
                    plan.getId(), step.getStepKey(), traceId, ex);
            step.markFailed(error, step.getResult());
            steps.saveAndFlush(step);
            recordEvent(plan.getId(), step.getId(), "step_failed", Map.of(
                    "stepKey", step.getStepKey(),
                    "error", error,
                    "traceId", traceId
            ));
        }
    }

    private void markInterruptedBatch(AgentPlan plan, List<AgentPlanStep> batch, String traceId) {
        for (AgentPlanStep step : batch) {
            if (AgentPlanStepStatus.PENDING.name().equals(step.getStatus())
                    || AgentPlanStepStatus.RUNNING.name().equals(step.getStatus())) {
                step.markFailed("Step batch interrupted before completion.");
                steps.saveAndFlush(step);
                recordEvent(plan.getId(), step.getId(), "step_failed", Map.of(
                        "stepKey", step.getStepKey(),
                        "error", step.getErrorMessage(),
                        "traceId", traceId
                ));
            }
        }
    }

    private int maxHarnessStepsForPlanStep(AgentSession session) {
        int configured = session.getMaxSteps() == null ? MAX_HARNESS_STEPS_PER_PLAN_STEP : session.getMaxSteps();
        return Math.max(1, Math.min(configured, MAX_HARNESS_STEPS_PER_PLAN_STEP));
    }

    private String buildVerificationFailureMessage(PlanStepVerifier.VerificationResult verification) {
        String reason = verification == null ? null : verification.reason();
        return "Step result did not satisfy success criteria: "
                + blankToDefault(reason, "Verifier rejected the candidate result.");
    }

    private boolean tryRepairFailedStep(AgentPlan plan,
                                        AgentSession session,
                                        List<AgentPlanStep> allSteps,
                                        AgentPlanStep failedStep,
                                        ResolvedSkill skill) {
        if (failedStep.getStepKey().startsWith(REPAIR_STEP_PREFIX)) {
            return false;
        }
        PlanningAgentPlanner.PlanSpec repairSpec;
        try {
            UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                    plan.getUserId(), session.getModelProviderSnapshot(), session.getModelSnapshot());
            repairSpec = planner.createRecoveryPlan(
                    plan.getGoal(),
                    buildRepairContext(allSteps, failedStep),
                    endpoint.providerKey(),
                    endpoint.modelName(),
                    endpoint.apiKey(),
                    endpoint.apiUrl(),
                    skill == null ? null : skill.prompt(),
                    skill == null ? null : List.copyOf(skill.allowedTools())
            );
        } catch (Exception ex) {
            recordEvent(plan.getId(), failedStep.getId(), "step_repair_failed", Map.of(
                    "stepKey", failedStep.getStepKey(),
                    "error", abbreviate(blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 1200)
            ));
            return false;
        }
        if (repairSpec == null || repairSpec.steps() == null || repairSpec.steps().isEmpty()) {
            recordEvent(plan.getId(), failedStep.getId(), "step_repair_unavailable", Map.of(
                    "stepKey", failedStep.getStepKey()
            ));
            return false;
        }

        List<PlanningAgentPlanner.StepSpec> repairSteps = repairSpec.steps().stream()
                .limit(MAX_REPAIR_STEPS)
                .toList();
        if (repairSteps.isEmpty()) {
            return false;
        }
        shiftSortOrdersAfterFailedStep(allSteps, failedStep, repairSteps.size());

        Set<String> existingKeys = allStepKeys(allSteps);
        List<String> failedDependencies = readStringList(failedStep.getDependenciesJson());
        Map<String, String> repairKeyMapping = new LinkedHashMap<>();
        List<AgentPlanStep> appendedSteps = new ArrayList<>();
        int nextOrder = failedStep.getSortOrder() + 1;
        String previousRepairKey = null;
        int repairIndex = 1;
        for (PlanningAgentPlanner.StepSpec repairStep : repairSteps) {
            String repairKey = nextRepairStepKey(failedStep.getStepKey(), repairIndex, existingKeys);
            repairKeyMapping.put(repairStep.id(), repairKey);
            List<String> dependencies = repairDependencies(
                    repairStep,
                    repairKeyMapping,
                    failedDependencies,
                    previousRepairKey,
                    existingKeys,
                    failedStep.getStepKey()
            );
            AgentPlanStep saved = steps.save(new AgentPlanStep(
                    plan.getId(),
                    repairKey,
                    nextOrder++,
                    repairStep.title(),
                    repairStep.description(),
                    repairStep.type(),
                    writeJson(dependencies),
                    writeJson(resolvePersistedAllowedTools(repairStep.allowedTools(), skill)),
                    repairStep.successCriteria()
            ));
            appendedSteps.add(saved);
            existingKeys.add(repairKey);
            previousRepairKey = repairKey;
            repairIndex++;
        }
        if (appendedSteps.isEmpty()) {
            return false;
        }

        String finalRepairKey = appendedSteps.get(appendedSteps.size() - 1).getStepKey();
        redirectDependents(allSteps, failedStep.getStepKey(), finalRepairKey);
        failedStep.markSuperseded("Superseded by recovery step " + finalRepairKey + ": " + failedStep.getErrorMessage());
        steps.saveAndFlush(failedStep);
        steps.flush();
        recordEvent(plan.getId(), failedStep.getId(), "plan_repaired", Map.of(
                "failedStepKey", failedStep.getStepKey(),
                "replacementStepKey", finalRepairKey,
                "addedStepCount", appendedSteps.size()
        ));
        return true;
    }

    private boolean tryDegradeFailedStep(AgentPlan plan, AgentPlanStep failedStep, String failedError) {
        if (!isVerificationFailure(failedError) || !StringUtils.hasText(failedStep.getResult())) {
            return false;
        }
        String warning = "Degraded after verification failure: "
                + blankToDefault(failedError, "Verifier rejected the candidate result.");
        String degradedResult = failedStep.getResult().trim()
                + "\n\n[Degraded warning]\n"
                + "This step did not fully satisfy the verifier. Downstream steps should compensate for the missing items.\n"
                + abbreviate(warning, 1200);
        failedStep.markDegraded(degradedResult, warning);
        steps.saveAndFlush(failedStep);
        recordEvent(plan.getId(), failedStep.getId(), "step_degraded", Map.of(
                "stepKey", failedStep.getStepKey(),
                "warning", abbreviate(warning, 1200),
                "result", abbreviate(degradedResult, 1200)
        ));
        return true;
    }

    private boolean isVerificationFailure(String error) {
        return StringUtils.hasText(error)
                && error.startsWith("Step result did not satisfy success criteria:");
    }

    private void shiftSortOrdersAfterFailedStep(List<AgentPlanStep> allSteps, AgentPlanStep failedStep, int repairStepCount) {
        for (AgentPlanStep step : allSteps) {
            if (step.getSortOrder() != null && step.getSortOrder() > failedStep.getSortOrder()) {
                step.updateSortOrder(step.getSortOrder() + repairStepCount);
                steps.save(step);
            }
        }
    }

    private String buildRepairContext(List<AgentPlanStep> allSteps, AgentPlanStep failedStep) {
        StringBuilder sb = new StringBuilder();
        sb.append("Failed step:\n")
                .append("- id: ").append(failedStep.getStepKey()).append("\n")
                .append("- title: ").append(blankToDefault(failedStep.getTitle(), "")).append("\n")
                .append("- description: ").append(failedStep.getDescription()).append("\n")
                .append("- success criteria: ").append(blankToDefault(failedStep.getSuccessCriteria(), "")).append("\n")
                .append("- error: ").append(blankToDefault(failedStep.getErrorMessage(), "")).append("\n\n")
                .append("Completed prerequisite or sibling results:\n");
        boolean hasCompletedResult = false;
        Set<String> failedDependencies = new LinkedHashSet<>(readStringList(failedStep.getDependenciesJson()));
        for (AgentPlanStep step : allSteps) {
            boolean relevant = failedDependencies.contains(step.getStepKey())
                    || AgentPlanStepStatus.COMPLETED.name().equals(step.getStatus());
            if (relevant && StringUtils.hasText(step.getResult())) {
                hasCompletedResult = true;
                sb.append("## ").append(step.getStepKey()).append(" ").append(blankToDefault(step.getTitle(), "")).append("\n")
                        .append(abbreviate(step.getResult(), 1200))
                        .append("\n\n");
            }
        }
        if (!hasCompletedResult) {
            sb.append("None.\n");
        }
        return sb.toString();
    }

    private Set<String> allStepKeys(List<AgentPlanStep> allSteps) {
        Set<String> keys = new LinkedHashSet<>();
        for (AgentPlanStep step : allSteps) {
            keys.add(step.getStepKey());
        }
        return keys;
    }

    private List<String> repairDependencies(PlanningAgentPlanner.StepSpec repairStep,
                                            Map<String, String> repairKeyMapping,
                                            List<String> failedDependencies,
                                            String previousRepairKey,
                                            Set<String> existingKeys,
                                            String failedStepKey) {
        List<String> dependencies = new ArrayList<>();
        for (String dependency : repairStep.dependencies()) {
            String mapped = repairKeyMapping.get(dependency);
            if (StringUtils.hasText(mapped) && !dependencies.contains(mapped)) {
                dependencies.add(mapped);
            } else if (existingKeys.contains(dependency)
                    && !failedStepKey.equals(dependency)
                    && !dependencies.contains(dependency)) {
                dependencies.add(dependency);
            }
        }
        if (dependencies.isEmpty()) {
            if (StringUtils.hasText(previousRepairKey)) {
                dependencies.add(previousRepairKey);
            } else {
                dependencies.addAll(failedDependencies);
            }
        }
        return dependencies;
    }

    private void redirectDependents(List<AgentPlanStep> allSteps, String oldDependency, String newDependency) {
        for (AgentPlanStep step : allSteps) {
            if (!AgentPlanStepStatus.PENDING.name().equals(step.getStatus())) {
                continue;
            }
            List<String> dependencies = readStringList(step.getDependenciesJson());
            if (!dependencies.contains(oldDependency)) {
                continue;
            }
            List<String> rewritten = dependencies.stream()
                    .map(dependency -> oldDependency.equals(dependency) ? newDependency : dependency)
                    .distinct()
                    .toList();
            step.updateDependenciesJson(writeJson(rewritten));
            steps.save(step);
            recordEvent(step.getPlanId(), step.getId(), "step_dependency_rewired", Map.of(
                    "stepKey", step.getStepKey(),
                    "oldDependency", oldDependency,
                    "newDependency", newDependency
            ));
        }
    }

    private String nextRepairStepKey(String failedStepKey, int repairIndex, Set<String> existingKeys) {
        String base = REPAIR_STEP_PREFIX + failedStepKey.replaceAll("[^A-Za-z0-9_]", "_") + "_" + repairIndex;
        if (base.length() > 56) {
            base = base.substring(0, 56);
        }
        String candidate = base;
        int suffix = 1;
        while (existingKeys.contains(candidate)) {
            String suffixText = "_" + suffix++;
            int maxBaseLength = Math.max(1, 64 - suffixText.length());
            candidate = base.substring(0, Math.min(base.length(), maxBaseLength)) + suffixText;
        }
        return candidate;
    }

    private void validatePlanSpec(PlanningAgentPlanner.PlanSpec spec) {
        if (spec == null || spec.steps() == null || spec.steps().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Planner did not generate any executable steps.");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (PlanningAgentPlanner.StepSpec step : spec.steps()) {
            if (!StringUtils.hasText(step.id()) || !ids.add(step.id())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Planner generated duplicate or blank step ids.");
            }
        }
        for (PlanningAgentPlanner.StepSpec step : spec.steps()) {
            for (String dependency : step.dependencies()) {
                if (!ids.contains(dependency)) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Planner generated a missing dependency: " + dependency);
                }
            }
        }
        if (hasDependencyCycle(spec.steps())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Planner generated a cyclic dependency graph.");
        }
    }

    private boolean hasDependencyCycle(List<PlanningAgentPlanner.StepSpec> planSteps) {
        Map<String, List<String>> graph = planSteps.stream()
                .collect(java.util.stream.Collectors.toMap(
                        PlanningAgentPlanner.StepSpec::id,
                        PlanningAgentPlanner.StepSpec::dependencies,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String id : graph.keySet()) {
            if (dfsCycle(id, graph, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsCycle(String id, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) {
            return false;
        }
        if (!visiting.add(id)) {
            return true;
        }
        for (String dependency : graph.getOrDefault(id, List.of())) {
            if (dfsCycle(dependency, graph, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    private List<AgentPlanStep> executableSteps(List<AgentPlanStep> allSteps) {
        return allSteps.stream()
                .filter(step -> AgentPlanStepStatus.PENDING.name().equals(step.getStatus()))
                .filter(step -> dependenciesCompleted(allSteps, step))
                .sorted(Comparator.comparing(AgentPlanStep::getSortOrder))
                .toList();
    }

    private boolean dependenciesCompleted(List<AgentPlanStep> allSteps, AgentPlanStep step) {
        Set<String> completed = new LinkedHashSet<>();
        for (AgentPlanStep item : allSteps) {
            if (AgentPlanStepStatus.COMPLETED.name().equals(item.getStatus())
                    || AgentPlanStepStatus.DEGRADED.name().equals(item.getStatus())) {
                completed.add(item.getStepKey());
            }
        }
        return completed.containsAll(readStringList(step.getDependenciesJson()));
    }

    private void markBlockedSteps(List<AgentPlanStep> allSteps, AgentPlanStep failedStep) {
        for (AgentPlanStep step : allSteps) {
            if (AgentPlanStepStatus.PENDING.name().equals(step.getStatus())
                    && readStringList(step.getDependenciesJson()).contains(failedStep.getStepKey())) {
                step.markSkipped("Dependency step failed: " + failedStep.getStepKey());
                steps.save(step);
                recordEvent(failedStep.getPlanId(), step.getId(), "step_skipped", Map.of(
                        "stepKey", step.getStepKey(),
                        "reason", step.getErrorMessage()
                ));
                markBlockedSteps(allSteps, step);
            }
        }
        steps.flush();
    }

    private List<String> resolvePersistedAllowedTools(List<String> stepAllowedTools, ResolvedSkill skill) {
        List<String> normalized = stepAllowedTools == null ? List.of() : stepAllowedTools;
        if (skill == null || skill.allowedTools() == null || skill.allowedTools().isEmpty()) {
            return normalized;
        }
        if (normalized.isEmpty()) {
            return List.copyOf(skill.allowedTools());
        }
        Set<String> intersection = new LinkedHashSet<>(normalized);
        intersection.retainAll(skill.allowedTools());
        return List.copyOf(intersection);
    }

    private List<String> resolveRuntimeAllowedTools(AgentPlanStep step, ResolvedSkill skill) {
        List<String> stepAllowed = readStringList(step.getAllowedToolsJson());
        if (StringUtils.hasText(step.getAllowedToolsJson())) {
            return stepAllowed.isEmpty() ? null : stepAllowed;
        }
        if (skill != null && skill.allowedTools() != null && !skill.allowedTools().isEmpty()) {
            return List.copyOf(skill.allowedTools());
        }
        return null;
    }

    private String buildStepSystemPrompt(AgentPlan plan,
                                         List<AgentPlanStep> allSteps,
                                         AgentPlanStep step,
                                         String previousError) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an isolated worker sub-agent for a Plan-and-Execute system.
                Complete only the current step. Do not jump to later steps.
                Use real tool calls only when needed. Do not emit fake tool-call markup.
                Use tools sparingly: one or two focused searches are usually enough, then synthesize.
                Stop as soon as the current step success criteria are met.
                Return a concise reusable result for downstream steps.
                If evidence is incomplete, state the limitation clearly instead of looping.

                Overall goal:
                """)
                .append(plan.getGoal()).append("\n\n")
                .append("Current step:\n")
                .append("- id: ").append(step.getStepKey()).append("\n")
                .append("- title: ").append(blankToDefault(step.getTitle(), "")).append("\n")
                .append("- type: ").append(blankToDefault(step.getType(), "")).append("\n")
                .append("- description: ").append(step.getDescription()).append("\n")
                .append("- success criteria: ").append(blankToDefault(step.getSuccessCriteria(), "")).append("\n\n")
                .append("Completed dependency results:\n");
        List<String> deps = readStringList(step.getDependenciesJson());
        boolean hasDependencyResult = false;
        for (AgentPlanStep item : allSteps) {
            if (deps.contains(item.getStepKey()) && StringUtils.hasText(item.getResult())) {
                hasDependencyResult = true;
                sb.append("## ").append(item.getStepKey()).append(" ")
                        .append(blankToDefault(item.getTitle(), "")).append("\n")
                        .append(abbreviate(item.getResult(), 1800)).append("\n\n");
            }
        }
        if (!hasDependencyResult) {
            sb.append("None.\n");
        }
        if (StringUtils.hasText(previousError)) {
            sb.append("\nPrevious attempt error:\n")
                    .append(abbreviate(previousError, 1200))
                    .append("\n");
        }
        return sb.toString();
    }

    private String buildStepUserMessage(AgentPlanStep step) {
        return "Execute the current plan step:\n" + step.getDescription()
                + "\n\nSuccess criteria:\n" + blankToDefault(step.getSuccessCriteria(), "");
    }

    private String buildFinalSummary(AgentPlan plan, List<AgentPlanStep> allSteps) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan: ").append(plan.getSummary()).append("\n");
        for (AgentPlanStep step : allSteps) {
            sb.append("- [").append(step.getStatus()).append("] ")
                    .append(step.getStepKey()).append(" ")
                    .append(blankToDefault(step.getTitle(), ""))
                    .append(": ")
                    .append(StringUtils.hasText(step.getResult())
                            ? abbreviate(step.getResult(), 300)
                            : blankToDefault(step.getErrorMessage(), "No result"))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private void persistConversationSummary(Long userId, Long sessionId, AgentPlan plan, List<AgentPlanStep> allSteps) {
        try {
            agentService.saveMessage(sessionId, userId, ChatMessage.user("/plan " + plan.getGoal()));
            agentService.saveMessage(sessionId, userId, ChatMessage.assistant(buildFinalSummary(plan, allSteps)));
        } catch (Exception ex) {
            log.warn("Failed to persist plan conversation summary planId={}", plan.getId(), ex);
        }
    }

    private void recordHarnessGuardrailEvent(AgentPlan plan, AgentPlanStep step, String error, String traceId) {
        if (!StringUtils.hasText(error)) {
            return;
        }
        String eventType = null;
        if (error.contains("Tool-call budget exceeded")) {
            eventType = "step_tool_budget_exceeded";
        } else if (error.contains("Duplicate tool call blocked")) {
            eventType = "step_duplicate_tool_call_blocked";
        }
        if (eventType != null) {
            recordEvent(plan.getId(), step.getId(), eventType, Map.of(
                    "stepKey", step.getStepKey(),
                    "error", abbreviate(error, 1200),
                    "traceId", traceId
            ));
        }
    }

    private void markPlanBudgetExceeded(AgentPlan plan,
                                        List<AgentPlanStep> allSteps,
                                        Long sessionId,
                                        Long userId,
                                        String traceId) {
        String error = "Plan execution budget exceeded after " + PLAN_BUDGET_SECONDS + " seconds.";
        for (AgentPlanStep step : allSteps) {
            if (AgentPlanStepStatus.PENDING.name().equals(step.getStatus())) {
                step.markSkipped(error);
                steps.save(step);
            } else if (AgentPlanStepStatus.RUNNING.name().equals(step.getStatus())
                    || AgentPlanStepStatus.REPAIRING.name().equals(step.getStatus())) {
                step.markFailed(error, step.getResult());
                steps.save(step);
            }
        }
        steps.flush();
        plan.markFailed(error);
        plans.saveAndFlush(plan);
        recordEvent(plan.getId(), null, "plan_budget_exceeded", Map.of(
                "traceId", traceId,
                "budgetSeconds", PLAN_BUDGET_SECONDS,
                "error", error
        ));
        persistConversationSummary(userId, sessionId, plan, steps.findByPlanIdOrderBySortOrderAsc(plan.getId()));
    }

    private boolean planCancelled(Long planId, Long userId) {
        return plans.findByIdAndUserId(planId, userId)
                .map(plan -> AgentPlanStatus.CANCELLED.name().equals(plan.getStatus()))
                .orElse(false);
    }

    private boolean deadlineExceeded(LocalDateTime deadlineAt) {
        return deadlineAt != null && LocalDateTime.now().isAfter(deadlineAt);
    }

    private ResolvedSkill resolveSkill(Long userId, String skillId) {
        return !StringUtils.hasText(skillId) ? null : skillsService.resolveEnabledSkill(userId, skillId.trim());
    }

    private AgentPlan getOwnedPlan(Long userId, Long planId) {
        return plans.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan does not exist."));
    }

    private AgentPlanResponse toResponse(AgentPlan plan, List<AgentPlanStep> planSteps) {
        return AgentPlanResponse.from(plan, planSteps.stream()
                .map(step -> AgentPlanStepResponse.from(
                        step,
                        readStringList(step.getDependenciesJson()),
                        readStringList(step.getAllowedToolsJson())
                ))
                .toList());
    }

    private void recordEvent(Long planId, Long stepId, String type, Map<String, ?> payload) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            if (payload != null) {
                JsonNode payloadNode = objectMapper.valueToTree(payload);
                if (payloadNode.isObject()) {
                    payloadNode.fields().forEachRemaining(entry -> node.set(entry.getKey(), entry.getValue()));
                }
            }
            events.save(new AgentPlanEvent(planId, stepId, type, objectMapper.writeValueAsString(node)));
        } catch (Exception ex) {
            log.warn("Failed to record plan event planId={} stepId={} type={}", planId, stepId, type, ex);
        }
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize plan field.", ex);
        }
    }

    private String newPlanTraceId(Long planId) {
        return "plan-" + planId + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void restoreTraceId(String previousTraceId) {
        if (previousTraceId == null) {
            MDC.remove("traceId");
        } else {
            MDC.put("traceId", previousTraceId);
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String blankToDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
