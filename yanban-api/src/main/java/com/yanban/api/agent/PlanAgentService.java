package com.yanban.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.agent.worker.ControlledPlanDispatchEnvelope;
import com.yanban.api.agent.worker.ControlledReadOnlyWorkerRuntimeAdapter;
import com.yanban.api.agent.worker.ControlledWorkerDispatch;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
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
import com.yanban.core.agent.AgentTaskOutcome;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ToolCall;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlanAgentService {

    private static final Logger log = LoggerFactory.getLogger(PlanAgentService.class);
    private static final int MAX_STEP_ATTEMPTS = 2;
    private static final int MAX_REPAIR_STEPS = 3;
    private static final int MAX_PARALLEL_STEPS = 3;
    private static final int MAX_DUPLICATE_TOOL_CALLS_PER_PLAN_STEP = 1;
    private static final int PLAN_EXECUTOR_THREADS = 2;
    private static final int PLAN_BUDGET_SECONDS = 240;
    private static final String REPAIR_STEP_PREFIX = "repair_";

    private final AgentPlanRepository plans;
    private final AgentPlanStepRepository steps;
    private final AgentPlanEventRepository events;
    private final AgentService agentService;
    private final AgentRuntimeService agentRuntimeService;
    private final AgentRuntimeCoordinator runtimeCoordinator;
    private final PlanningAgentPlanner planner;
    private final PlanStepVerifier stepVerifier;
    private final UserSettingsService userSettingsService;
    private final SkillsService skillsService;
    private final AgentToolPolicyEngine toolPolicyEngine;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;
    private final ControlledReadOnlyWorkerRuntimeAdapter controlledWorkerExecutor;
    private final ExecutorService planExecutor = Executors.newFixedThreadPool(PLAN_EXECUTOR_THREADS);

    @Autowired
    public PlanAgentService(AgentPlanRepository plans,
                            AgentPlanStepRepository steps,
                            AgentPlanEventRepository events,
                            AgentService agentService,
                            AgentRuntimeService agentRuntimeService,
                            @Lazy AgentRuntimeCoordinator runtimeCoordinator,
                            PlanningAgentPlanner planner,
                            PlanStepVerifier stepVerifier,
                            UserSettingsService userSettingsService,
                            SkillsService skillsService,
                             AgentToolPolicyEngine toolPolicyEngine,
                             ObjectMapper objectMapper,
                             ProjectService projectService,
                             ControlledReadOnlyWorkerRuntimeAdapter controlledWorkerExecutor) {
        this.plans = plans;
        this.steps = steps;
        this.events = events;
        this.agentService = agentService;
        this.agentRuntimeService = agentRuntimeService;
        this.runtimeCoordinator = runtimeCoordinator;
        this.planner = planner;
        this.stepVerifier = stepVerifier;
        this.userSettingsService = userSettingsService;
        this.skillsService = skillsService;
        this.toolPolicyEngine = toolPolicyEngine;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
        this.controlledWorkerExecutor = controlledWorkerExecutor;
    }

    /** Source-compatible constructor for focused tests that do not execute a controlled Worker Plan. */
    public PlanAgentService(AgentPlanRepository plans,
                            AgentPlanStepRepository steps,
                            AgentPlanEventRepository events,
                            AgentService agentService,
                            AgentRuntimeService agentRuntimeService,
                            AgentRuntimeCoordinator runtimeCoordinator,
                            PlanningAgentPlanner planner,
                            PlanStepVerifier stepVerifier,
                            UserSettingsService userSettingsService,
                            SkillsService skillsService,
                            AgentToolPolicyEngine toolPolicyEngine,
                            ObjectMapper objectMapper,
                            ProjectService projectService) {
        this(plans, steps, events, agentService, agentRuntimeService, runtimeCoordinator, planner, stepVerifier,
                userSettingsService, skillsService, toolPolicyEngine, objectMapper, projectService, null);
    }

    /** Source-compatible constructor for existing direct construction without Project capability. */
    public PlanAgentService(AgentPlanRepository plans, AgentPlanStepRepository steps, AgentPlanEventRepository events,
                            AgentService agentService, AgentRuntimeService agentRuntimeService,
                            AgentRuntimeCoordinator runtimeCoordinator, PlanningAgentPlanner planner,
                            PlanStepVerifier stepVerifier, UserSettingsService userSettingsService, SkillsService skillsService,
                            AgentToolPolicyEngine toolPolicyEngine, ObjectMapper objectMapper) {
        this(plans, steps, events, agentService, agentRuntimeService, runtimeCoordinator, planner, stepVerifier,
                userSettingsService, skillsService, toolPolicyEngine, objectMapper, null, null);
    }

    /** Compatibility constructor retained for focused scheduler tests and non-Spring callers. */
    public PlanAgentService(AgentPlanRepository plans,
                            AgentPlanStepRepository steps,
                            AgentPlanEventRepository events,
                            AgentService agentService,
                            AgentRuntimeService agentRuntimeService,
                            PlanningAgentPlanner planner,
                            PlanStepVerifier stepVerifier,
                            UserSettingsService userSettingsService,
                            SkillsService skillsService,
                            AgentToolPolicyEngine toolPolicyEngine,
                            ObjectMapper objectMapper) {
        this(plans, steps, events, agentService, agentRuntimeService, null, planner, stepVerifier,
                userSettingsService, skillsService, toolPolicyEngine, objectMapper, null, null);
    }

    @PreDestroy
    void shutdownPlanExecutor() {
        planExecutor.shutdownNow();
    }

    @Transactional
    public AgentPlanResponse createPlan(Long userId, Long sessionId, CreateAgentPlanRequest request) {
        if (runtimeCoordinator == null) {
            return createPlanInternal(userId, sessionId, request);
        }
        AgentSession session = agentService.getOwnedSession(userId, sessionId);
        boolean ragDisabled = request.ragDisabled() != null
                ? request.ragDisabled()
                : Boolean.TRUE.equals(session.getRagDisabled());
        ResolvedSkill skill = resolveSkill(userId, request.skillId());
        ResolvedToolPolicy planToolPolicy = resolvePlanToolPolicy(request.content(), ragDisabled, skill);
        UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                userId, session.getModelProviderSnapshot(), session.getModelSnapshot());
        AgentRuntimeRequest runtimeRequest = new AgentRuntimeRequest(
                null, sessionId, List.of(), userId, request.content(), endpoint.providerKey(), endpoint.modelName(),
                null, null, 1, ragDisabled, skill == null ? null : skill.id(), endpoint.apiKey(), endpoint.apiUrl(),
                skill == null ? null : skill.prompt(), AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, planToolPolicy, null, null,
                newPlanTraceId(null), null, null);
        AgentCoordinationResult coordination = runtimeCoordinator.coordinate(
                AgentCoordinationRequest.trustedPlanCreate(runtimeRequest));
        Long createdPlanId = coordination.runtimeResult().planId();
        if (coordination.runProjection().state().outcome() == AgentTaskOutcome.FAILED || createdPlanId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    blankToDefault(coordination.runtimeResult().errorMessage(), "Planner did not create an executable plan."));
        }
        AgentPlanResponse created = getPlan(userId, createdPlanId);
        return Boolean.TRUE.equals(request.autoExecute()) ? executePlanAsync(userId, createdPlanId) : created;
    }

    /** Only the authenticated Project facade calls this; no caller-supplied evidence is trusted. */
    @Transactional
    public AgentPlanResponse createProjectPlan(Long userId, Long projectId, Long sessionId, CreateAgentPlanRequest request) {
        String traceId = newPlanTraceId(null);
        log.info("Project Plan create start traceId={} userId={} projectId={} sessionId={} autoExecute={}",
                traceId, userId, projectId, sessionId, request == null ? null : request.autoExecute());
        ProjectRuntimeContext context = revalidateProject(userId, projectId);
        AgentSession session = agentService.getOwnedSession(userId, sessionId);
        boolean ragDisabled = request.ragDisabled() != null ? request.ragDisabled() : Boolean.TRUE.equals(session.getRagDisabled());
        ResolvedSkill skill = resolveSkill(userId, request.skillId());
        UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(userId,
                session.getModelProviderSnapshot(), session.getModelSnapshot());
        AgentRuntimeRequest runtimeRequest = new AgentRuntimeRequest(null, sessionId, List.of(), userId, request.content(),
                endpoint.providerKey(), endpoint.modelName(), null, null, 1, ragDisabled, skill == null ? null : skill.id(),
                endpoint.apiKey(), endpoint.apiUrl(), skill == null ? null : skill.prompt(), AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, toolPolicyEngine.decideProject(skill == null ? null : skill.allowedTools(), null).resolved(),
                null, null, traceId, null, null).withProjectContext(context);
        log.info("Project Plan runtime resolved traceId={} userId={} projectId={} sessionId={} provider={} model={} allowedTools={}",
                traceId, userId, projectId, sessionId, endpoint.providerKey(), endpoint.modelName(),
                runtimeRequest.toolPolicy().allowedTools());
        AgentCoordinationResult coordination = runtimeCoordinator == null
                ? null : runtimeCoordinator.coordinate(AgentCoordinationRequest.trustedProjectPlanCreate(runtimeRequest));
        AgentRuntimeResult runtimeResult = coordination == null ? null : coordination.runtimeResult();
        if (runtimeResult == null || coordination.runProjection().state().outcome() == AgentTaskOutcome.FAILED
                || runtimeResult.planId() == null) {
            String error = runtimeResult == null
                    ? "Project Plan runtime is unavailable."
                    : blankToDefault(runtimeResult.errorMessage(), "Project planner failed.");
            log.warn("Project Plan create failed traceId={} userId={} projectId={} sessionId={} stopReason={} outcome={} error={}",
                    traceId, userId, projectId, sessionId,
                    runtimeResult == null ? null : runtimeResult.stopReason(),
                    runtimeResult == null ? null : runtimeResult.outcome(),
                    abbreviate(error, 500));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Project Plan creation failed [traceId=" + traceId + "]: " + error);
        }
        AgentPlanResponse created = getPlan(userId, runtimeResult.planId());
        log.info("Project Plan created traceId={} userId={} projectId={} sessionId={} planId={} autoExecute={}",
                traceId, userId, projectId, sessionId, created.id(), request.autoExecute());
        return Boolean.TRUE.equals(request.autoExecute()) ? executePlanAsync(userId, created.id()) : created;
    }

    /** Called only by {@link PlanRuntimeAdapter} after trusted Coordinator selection. */
    AgentPlanResponse createPlanWithinAdapter(AgentRuntimeRequest request) {
        if (request.controlledWorkerDispatch() != null) {
            request.controlledWorkerDispatch().validateAgainst(request);
        }
        return createPlanInternal(request.userId(), request.sessionId(), new CreateAgentPlanRequest(
                request.userMessage(), request.ragDisabled(), request.skillId(), false), request.projectContext(),
                request.orchestrationRequirements(), request.toolPolicy(), request.controlledWorkerDispatch());
    }

    private AgentPlanResponse createPlanInternal(Long userId, Long sessionId, CreateAgentPlanRequest request) {
        return createPlanInternal(userId, sessionId, request, null);
    }

    private AgentPlanResponse createPlanInternal(Long userId, Long sessionId, CreateAgentPlanRequest request,
                                                  ProjectRuntimeContext projectContext) {
        return createPlanInternal(userId, sessionId, request, projectContext, AgentOrchestrationRequirements.empty());
    }

    private AgentPlanResponse createPlanInternal(Long userId, Long sessionId, CreateAgentPlanRequest request,
                                                 ProjectRuntimeContext projectContext,
                                                 AgentOrchestrationRequirements orchestrationRequirements) {
        return createPlanInternal(userId, sessionId, request, projectContext, orchestrationRequirements, null);
    }

    private AgentPlanResponse createPlanInternal(Long userId, Long sessionId, CreateAgentPlanRequest request,
                                                 ProjectRuntimeContext projectContext,
                                                 AgentOrchestrationRequirements orchestrationRequirements,
                                                 ResolvedToolPolicy runtimePolicyCeiling) {
        return createPlanInternal(userId, sessionId, request, projectContext, orchestrationRequirements,
                runtimePolicyCeiling, null);
    }

    private AgentPlanResponse createPlanInternal(Long userId, Long sessionId, CreateAgentPlanRequest request,
                                                 ProjectRuntimeContext projectContext,
                                                 AgentOrchestrationRequirements orchestrationRequirements,
                                                 ResolvedToolPolicy runtimePolicyCeiling,
                                                 ControlledWorkerDispatch controlledDispatch) {
        if (projectContext != null) projectContext = revalidateProject(userId, projectContext.projectId());
        AgentSession session = agentService.getOwnedSession(userId, sessionId);
        boolean ragDisabled = request.ragDisabled() != null
                ? request.ragDisabled()
                : Boolean.TRUE.equals(session.getRagDisabled());
        ResolvedSkill skill = resolveSkill(userId, request.skillId());
        ResolvedToolPolicy planToolPolicy = projectContext == null
                ? resolvePlanToolPolicy(request.content(), ragDisabled, skill)
                : toolPolicyEngine.decideProject(skill == null ? null : skill.allowedTools(), null).resolved();
        planToolPolicy = constrainToRuntimePolicy(planToolPolicy, runtimePolicyCeiling);
        PlanningAgentPlanner.PlanSpec spec;
        JsonNode controlledEnvelope = null;
        if (controlledDispatch != null) {
            controlledEnvelope = ControlledPlanDispatchEnvelope.capture(objectMapper, controlledDispatch);
            spec = controlledPlanSpec(controlledDispatch, controlledEnvelope);
        } else {
            UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                    userId, session.getModelProviderSnapshot(), session.getModelSnapshot());
            if (orchestrationRequirements == null || orchestrationRequirements.materialRequirements().isEmpty()) {
                spec = planner.createPlan(request.content(), endpoint.providerKey(), endpoint.modelName(),
                        endpoint.apiKey(), endpoint.apiUrl(), skill == null ? null : skill.prompt(),
                        planToolPolicy.allowedTools());
            } else {
                spec = planner.createPlan(request.content(), endpoint.providerKey(), endpoint.modelName(),
                        endpoint.apiKey(), endpoint.apiUrl(), skill == null ? null : skill.prompt(),
                        planToolPolicy.allowedTools(), orchestrationRequirements);
            }
        }
        if (spec == null || spec.failureCode() != null) {
            PlannerFailureCode code = spec == null ? PlannerFailureCode.INVALID_PLAN : spec.failureCode();
            String message = spec == null ? "Planner returned no result." : spec.failureMessage();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Planner failed [" + code + "]: " + blankToDefault(message, "No executable plan was produced."));
        }
        validatePlanSpec(spec);

        AgentPlan plan = new AgentPlan(
                sessionId,
                userId,
                request.content().trim(),
                spec.summary(),
                ragDisabled,
                skill == null ? null : skill.id(),
                controlledEnvelope == null
                        ? ProjectPlanEnvelope.wrap(objectMapper, spec.rawJson(), projectContext)
                        : ProjectPlanEnvelope.wrapControlled(
                                objectMapper, spec.rawJson(), projectContext, controlledEnvelope)
        );
        plan = plans.saveAndFlush(plan);

        List<AgentPlanStep> savedSteps = new ArrayList<>();
        int order = 1;
        for (PlanningAgentPlanner.StepSpec step : spec.steps()) {
            // A controlled Plan step is itself part of the attested envelope. Persist its
            // exact server-planned Worker tool union; the ordinary semantic expansion below
            // is intentionally reserved for model-planned Project steps.
            List<String> allowedTools = controlledDispatch == null
                    ? resolvePersistedStepAllowedTools(step, planToolPolicy, projectContext != null)
                    : resolvePersistedAllowedTools(step.allowedTools(), planToolPolicy);
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
                "stepCount", savedSteps.size(),
                "projectId", projectContext == null ? "" : projectContext.projectId(),
                "capability", projectContext == null ? "" : "PROJECT_READ"
        ));

        return toResponse(plan, savedSteps);
    }

    private PlanningAgentPlanner.PlanSpec controlledPlanSpec(ControlledWorkerDispatch dispatch,
                                                              JsonNode controlledEnvelope) {
        LinkedHashSet<String> allowedTools = new LinkedHashSet<>();
        List<String> scopedPaths = new ArrayList<>();
        dispatch.tasks().forEach(task -> {
            allowedTools.addAll(task.attestation().packet().allowedReadTools());
            task.attestation().packet().materialScope().forEach(path -> scopedPaths.add(path.value()));
        });
        scopedPaths.sort(String::compareTo);
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("schema", "controlled_read_only_worker_plan_v1");
        raw.put("projectVersion", dispatch.projectVersion().value());
        raw.put("workerCount", dispatch.tasks().size());
        raw.set("relativePaths", objectMapper.valueToTree(scopedPaths));
        String description = "Execute the server-bounded read-only paper and implementation Workers for exactly "
                + "these current Project relative paths:\n- " + String.join("\n- ", scopedPaths);
        String digestMarker = ControlledPlanDispatchEnvelope.digestMarker(objectMapper, controlledEnvelope);
        PlanningAgentPlanner.StepSpec step = new PlanningAgentPlanner.StepSpec(
                ControlledPlanDispatchEnvelope.STEP_KEY, "Controlled cross-material read-only analysis", description,
                "ANALYSIS", List.of(), List.copyOf(allowedTools),
                "Persist current versioned Evidence and one canonical parent synthesis; semantic differences remain "
                        + "PARTIAL. " + digestMarker);
        return new PlanningAgentPlanner.PlanSpec("Controlled read-only cross-material analysis",
                List.of(step), raw.toString());
    }

    private ResolvedToolPolicy constrainToRuntimePolicy(ResolvedToolPolicy resolved,
                                                        ResolvedToolPolicy runtimePolicyCeiling) {
        if (runtimePolicyCeiling == null) return resolved;
        Set<String> ceiling = Set.copyOf(runtimePolicyCeiling.allowedTools());
        List<String> allowed = resolved.allowedTools().stream().filter(ceiling::contains).toList();
        int maxToolCalls = Math.min(resolved.maxToolCalls(), runtimePolicyCeiling.maxToolCalls());
        int maxDuplicateToolCalls = Math.min(resolved.maxDuplicateToolCalls(),
                runtimePolicyCeiling.maxDuplicateToolCalls());
        return new ResolvedToolPolicy(allowed, maxToolCalls, maxDuplicateToolCalls,
                resolved.reason() + "+runtime_policy_ceiling");
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
        // Fail closed before changing async state, and fully reissue controlled authority from persistence.
        validatePlanExecutionBoundary(plan, userId, traceId);
        recordEvent(plan.getId(), null, "plan_queued", Map.of("traceId", traceId, "mode", "async"));
        plan.markRunning();
        plans.saveAndFlush(plan);
        scheduleAsyncExecution(userId, planId, traceId);
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
        // Do not reset steps/status if the persisted envelope, Project, or current authority cannot be recovered.
        validatePlanExecutionBoundary(plan, userId, newPlanTraceId(planId));

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
            executePlanThroughCoordinator(userId, planId, traceId, true);
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

    void scheduleAsyncExecution(Long userId, Long planId, String traceId) {
        Runnable task = () -> submitAsyncExecutionTask(userId, planId, traceId);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    void submitAsyncExecutionTask(Long userId, Long planId, String traceId) {
        planExecutor.submit(() -> runPlanAsyncWorker(userId, planId, traceId));
    }

    public AgentPlanResponse executePlan(Long userId, Long planId) {
        return executePlanThroughCoordinator(userId, planId, newPlanTraceId(planId), true);
    }

    PlanExecutionResult createAndExecuteRuntimeReflectionPlan(AgentRuntimeRequest request, String goal) {
        AgentPlanResponse created = createPlanInternal(request.userId(), request.sessionId(),
                new CreateAgentPlanRequest(goal, request.ragDisabled(), request.skillId(), false),
                request.projectContext(), request.orchestrationRequirements(), request.toolPolicy());
        AgentPlanResponse executed = executePlanThroughCoordinator(
                request.userId(), created.id(), request.traceId(), false);
        return new PlanExecutionResult(executed, AgentRuntimeStopSignal.NONE,
                evidenceFromStepEvents(executed.id()), domainRuntimeFacts(executed));
    }

    /** Called only by {@link PlanRuntimeAdapter} after the Coordinator has selected PLAN_EXECUTE. */
    AgentPlanResponse executePlanWithinAdapter(Long userId, Long planId, String traceId,
                                                boolean persistConversationSummary) {
        return executePlanInternal(userId, planId, traceId, persistConversationSummary).plan();
    }

    PlanExecutionResult executePlanResultWithinAdapter(Long userId, Long planId, String traceId,
                                                       boolean persistConversationSummary) {
        return executePlanInternal(userId, planId, traceId, persistConversationSummary);
    }

    PlanExecutionResult executePlanResultWithinAdapter(AgentRuntimeRequest parentRequest, Long planId,
                                                       String traceId, boolean persistConversationSummary) {
        if (parentRequest == null || parentRequest.planId() == null || !parentRequest.planId().equals(planId)) {
            throw new IllegalArgumentException("controlled Plan execution requires its persisted parent identity");
        }
        AgentPlan plan = getOwnedPlan(parentRequest.userId(), planId);
        AgentRuntimeRequest recovered = restoreControlledExecutionRequest(
                plan, parentRequest.userId(), traceId, persistConversationSummary);
        if (recovered == null) {
            throw new IllegalArgumentException("persisted Plan does not contain a controlled Worker envelope");
        }
        return executePlanInternal(parentRequest.userId(), planId, traceId,
                persistConversationSummary, recovered);
    }

    private AgentPlanResponse executePlanThroughCoordinator(Long userId, Long planId, String traceId,
                                                             boolean persistConversationSummary) {
        AgentPlan plan = getOwnedPlan(userId, planId);
        if (AgentPlanStatus.PAUSED.name().equals(plan.getStatus())) {
            recordEvent(planId, null, "plan_execution_paused", Map.of("traceId", traceId, "outcome", "PAUSED"));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan is paused and cannot be executed.");
        }
        AgentRuntimeRequest recoveredControlled = restoreControlledExecutionRequest(
                plan, userId, traceId, persistConversationSummary);
        if (runtimeCoordinator == null) {
            return executePlanInternal(userId, planId, traceId, persistConversationSummary,
                    recoveredControlled).plan();
        }
        AgentSession session = agentService.getOwnedSession(userId, plan.getSessionId());
        ResolvedSkill skill = resolveSkill(userId, plan.getSkillId());
        UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                userId, session.getModelProviderSnapshot(), session.getModelSnapshot());
        ProjectRuntimeContext projectContext = restoreProjectContext(plan, userId);
        ResolvedToolPolicy policy = projectContext == null
                ? resolvePlanToolPolicy(plan.getGoal(), Boolean.TRUE.equals(plan.getRagDisabled()), skill)
                : toolPolicyEngine.decideProject(skill == null ? null : skill.allowedTools(), null).resolved();
        AgentRuntimeRequest request = new AgentRuntimeRequest(
                null, session.getId(), List.of(), userId, plan.getGoal(), endpoint.providerKey(), endpoint.modelName(),
                null, null, 1, Boolean.TRUE.equals(plan.getRagDisabled()), skill == null ? null : skill.id(),
                endpoint.apiKey(), endpoint.apiUrl(), skill == null ? null : skill.prompt(), AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, policy, null, null, traceId, null, null);
        request = request.withPlanConversationSummaryPersistence(persistConversationSummary);
        if (projectContext != null) request = request.withProjectContext(projectContext);
        if (recoveredControlled != null) request = recoveredControlled;
        AgentCoordinationResult result = runtimeCoordinator.coordinate(
                projectContext == null ? AgentCoordinationRequest.trustedPlanApi(request, planId)
                        : AgentCoordinationRequest.trustedProjectPlan(request, planId));
        boolean infrastructureFailure = result.runtimeResult().stopReason() == AgentStopReason.POLICY_REJECTED
                || result.runtimeResult().stopReason() == AgentStopReason.NO_RUNTIME_ADAPTER
                || result.runtimeResult().stopReason() == AgentStopReason.RUNTIME_EXCEPTION;
        if (infrastructureFailure && result.runProjection().state().outcome() == AgentTaskOutcome.FAILED) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    blankToDefault(result.runtimeResult().errorMessage(), "Plan runtime could not be started."));
        }
        return getPlan(userId, planId);
    }

    private PlanExecutionResult executePlanInternal(Long userId, Long planId, String traceId, boolean persistConversationSummary) {
        return executePlanInternal(userId, planId, traceId, persistConversationSummary, null);
    }

    private PlanExecutionResult executePlanInternal(Long userId, Long planId, String traceId,
                                                    boolean persistConversationSummary,
                                                    AgentRuntimeRequest controlledParentRequest) {
        String previousTraceId = MDC.get("traceId");
        MDC.put("traceId", traceId);
        try {
            AgentPlan plan = getOwnedPlan(userId, planId);
            if (plan.terminal()) {
                return completedExecution(toResponse(plan, steps.findByPlanIdOrderBySortOrderAsc(plan.getId())));
            }
            if (AgentPlanStatus.PAUSED.name().equals(plan.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan is paused and cannot be executed.");
            }

            AgentSession session = agentService.getOwnedSession(userId, plan.getSessionId());
            ResolvedSkill skill = resolveSkill(userId, plan.getSkillId());
            ProjectRuntimeContext projectContext = restoreProjectContext(plan, userId);
            String projectManifestSummary = projectContext == null ? ""
                    : buildPlanManifestSummary(userId, projectContext.projectId());
            Map<String, String> sharedExecutionState = restoreExecutionState(plan.getId());
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
                    if (persistConversationSummary) {
                        persistConversationSummary(userId, session.getId(), cancelled, allSteps);
                    }
                    return completedExecution(toResponse(cancelled, allSteps));
                }
                if (deadlineExceeded(deadlineAt)) {
                    markPlanBudgetExceeded(plan, allSteps, session.getId(), userId, traceId, persistConversationSummary);
                    allSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
                    AgentPlanResponse response = toResponse(plan, allSteps);
                    return new PlanExecutionResult(response, AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED,
                            evidenceFromStepEvents(plan.getId()), domainRuntimeFacts(response));
                }

                AgentPlanStep failedStep = firstFailedStep(allSteps);
                if (failedStep != null) {
                    if (controlledParentRequest == null
                            && recoverFailedStep(plan, session, allSteps, failedStep, skill, projectContext)) {
                        allSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
                        continue;
                    }
                    String failedError = failedStep.getErrorMessage();
                    failedStep.markFailed(failedError, failedStep.getResult());
                    steps.saveAndFlush(failedStep);
                    markBlockedSteps(allSteps, failedStep);
                    allSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
                    preserveBoundedFailureSynthesis(plan, allSteps, failedStep);
                    allSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
                    plan.markFailed("Step " + failedStep.getStepKey() + " failed: " + failedStep.getErrorMessage());
                    plans.saveAndFlush(plan);
                    recordEvent(plan.getId(), failedStep.getId(), "plan_failed", Map.of(
                            "traceId", traceId,
                            "error", plan.getErrorMessage()
                    ));
                    if (persistConversationSummary) {
                        persistConversationSummary(userId, session.getId(), plan, allSteps);
                    }
                    return completedExecution(toResponse(plan, allSteps));
                }

                List<AgentPlanStep> executable = executableSteps(allSteps);
                if (executable.isEmpty()) {
                    break;
                }
                List<AgentPlanStep> batch = executable.stream()
                        .limit(MAX_PARALLEL_STEPS)
                        .toList();
                executeStepBatch(plan, session, allSteps, batch, skill, projectContext, projectManifestSummary,
                        sharedExecutionState, deadlineAt, traceId, controlledParentRequest);
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
            if (persistConversationSummary) {
                persistConversationSummary(userId, session.getId(), plan, allSteps);
            }
            return completedExecution(toResponse(plan, allSteps));
        } finally {
            restoreTraceId(previousTraceId);
        }
    }

    private PlanExecutionResult completedExecution(AgentPlanResponse plan) {
        return new PlanExecutionResult(plan, AgentRuntimeStopSignal.NONE, evidenceFromStepEvents(plan.id()),
                domainRuntimeFacts(plan));
    }

    record PlanExecutionResult(AgentPlanResponse plan, AgentRuntimeStopSignal stopSignal,
                               EvidenceLedger evidenceLedger, DomainRuntimeFacts domainRuntimeFacts) {
        PlanExecutionResult(AgentPlanResponse plan, AgentRuntimeStopSignal stopSignal) {
            this(plan, stopSignal, EvidenceLedger.empty(), DomainRuntimeFacts.empty());
        }

        PlanExecutionResult(AgentPlanResponse plan, AgentRuntimeStopSignal stopSignal, EvidenceLedger evidenceLedger) {
            this(plan, stopSignal, evidenceLedger, DomainRuntimeFacts.empty());
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
                                      ResolvedSkill skill,
                                      ProjectRuntimeContext projectContext) {
        String failedError = failedStep.getErrorMessage();
        failedStep.markRepairing(failedError);
        steps.saveAndFlush(failedStep);
        recordEvent(plan.getId(), failedStep.getId(), "step_repair_started", Map.of(
                "stepKey", failedStep.getStepKey(),
                "error", blankToDefault(failedError, "Step failed.")
        ));
        if (tryRepairFailedStep(plan, session, allSteps, failedStep, skill, projectContext)) {
            return true;
        }
        return tryDegradeFailedStep(plan, failedStep, failedError);
    }

    private void executeStep(AgentPlan plan,
                             AgentSession session,
                             List<AgentPlanStep> allSteps,
                             AgentPlanStep step,
                             ResolvedSkill skill,
                             ProjectRuntimeContext projectContext,
                             String projectManifestSummary,
                              Map<String, String> sharedExecutionState,
                              LocalDateTime deadlineAt,
                              String traceId,
                              AgentRuntimeRequest controlledParentRequest) {
        recordEvent(plan.getId(), step.getId(), "step_started", Map.of(
                "stepKey", step.getStepKey(),
                "title", blankToDefault(step.getTitle(), ""),
                "traceId", traceId
        ));
        String previousError = step.getErrorMessage();
        String executionStateSummary = sharedExecutionState.getOrDefault(step.getStepKey(), "");
        if (preserveMissingMaterialDependencySynthesis(plan, allSteps, step)) {
            return;
        }
        Set<String> requiredMaterialPaths = requiredProjectMaterialPaths(step);
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
            JsonNode persistedControlled = ProjectPlanEnvelope.restoreControlled(
                    objectMapper, plan.getRawPlanJson(), plan.getUserId());
            if (persistedControlled == null && hasControlledEnvelopeMarker(step)) {
                String error = "Controlled Worker envelope is missing from its persisted Plan.";
                step.markFailed(error);
                recordEvent(plan.getId(), step.getId(), "step_failed", Map.of(
                        "stepKey", step.getStepKey(), "error", error, "traceId", traceId));
                return;
            }
            if (persistedControlled != null && !isControlledPlanStep(plan, step, controlledParentRequest)) {
                String error = "Controlled Worker dispatch is unavailable or does not match its persisted Plan.";
                step.markFailed(error);
                recordEvent(plan.getId(), step.getId(), "step_failed", Map.of(
                        "stepKey", step.getStepKey(), "error", error, "traceId", traceId));
                return;
            }
            if (persistedControlled != null) {
                executeControlledPlanStep(plan, allSteps, step, projectContext, traceId,
                        attempt + 1, controlledParentRequest);
                return;
            }
            step.markRunning();
            steps.saveAndFlush(step);
            UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                    plan.getUserId(), session.getModelProviderSnapshot(), session.getModelSnapshot());
            ResolvedToolPolicy runtimeToolPolicy = resolveRuntimeToolPolicy(step, plan, skill, projectContext);
            AgentRuntimeRequest runtimeRequest = new AgentRuntimeRequest(
                    AgentStrategy.SINGLE_STEP_REACT,
                    session.getId(),
                    List.of(ChatMessage.system(buildStepSystemPrompt(plan, allSteps, step, previousError,
                            executionStateSummary, projectManifestSummary, sharedExecutionState,
                            runtimeToolPolicy.allowedTools()))),
                    plan.getUserId(),
                    buildStepUserMessage(step),
                    endpoint.providerKey(),
                    endpoint.modelName(),
                    null,
                    null,
                    maxRuntimeStepsForPlanStep(session, runtimeToolPolicy, projectContext != null),
                    Boolean.TRUE.equals(plan.getRagDisabled()),
                    skill == null ? null : skill.id(),
                    endpoint.apiKey(),
                    endpoint.apiUrl(),
                    skill == null ? null : skill.prompt(),
                    AgentRuntimeMode.LANGCHAIN4J,
                    AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                    runtimeToolPolicy,
                    null,
                    null,
                    traceId,
                    null,
                    null
            );
            if (projectContext != null) {
                ProjectRuntimeContext currentProjectContext = revalidateProject(
                        plan.getUserId(), projectContext.projectId());
                EvidenceLedger currentDependencyEvidence = new ProjectEvidenceValidator(projectService).current(
                        plan.getUserId(), currentProjectContext,
                        dependencyEvidence(plan.getId(), allSteps, step));
                runtimeRequest = runtimeRequest
                        .withProjectContext(currentProjectContext)
                        .withInheritedTrustedEvidence(currentDependencyEvidence);
            }
            AgentRuntimeResult result;
            try {
                result = agentRuntimeService.run(runtimeRequest);
            } catch (Exception ex) {
                String error = "Step worker execution crashed: "
                        + abbreviate(blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 1200);
                log.warn("Plan step worker crashed planId={} stepKey={} traceId={}",
                        plan.getId(), step.getStepKey(), traceId, ex);
                result = new AgentRuntimeResult(
                        false,
                        null,
                        List.of(),
                        attempt + 1,
                        error,
                        List.of(),
                        List.of(error),
                        null,
                        null,
                        null
                );
            }
            recordToolObservations(plan, step, result, projectContext, traceId, attempt + 1);
            executionStateSummary = mergeExecutionState(executionStateSummary, result);
            sharedExecutionState.put(step.getStepKey(), executionStateSummary);
            String missingTarget = missingTargetProjectFile(result, requiredMaterialPaths);
            if (StringUtils.hasText(missingTarget)) {
                String content = StringUtils.hasText(result.assistantContent())
                        ? result.assistantContent()
                        : "The explicitly requested Project file is unavailable: " + missingTarget;
                String warning = ProjectMaterialScope.MISSING_TARGET_PREFIX + " " + missingTarget;
                step.markDegraded(content, warning);
                recordEvent(plan.getId(), step.getId(), "step_degraded_missing_target_material", Map.of(
                        "stepKey", step.getStepKey(),
                        "relativePath", missingTarget,
                        "reason", warning,
                        "traceId", traceId
                ));
                return;
            }
            if (result.success()) {
                String content = StringUtils.hasText(result.assistantContent())
                        ? result.assistantContent()
                        : "Step completed.";
                List<EvidenceRef> typedEvidence = projectContext == null ? List.of() : trustedStepEvidence(
                        result, projectContext, runtimeRequest.toolPolicy().allowedTools(),
                        runtimeRequest.inheritedTrustedEvidence(), plan, step, attempt + 1, requiredMaterialPaths);
                List<String> projectEvidenceRefs = projectContext == null ? List.of() : extractProjectEvidenceRefs(
                        result, projectContext, runtimeRequest.toolPolicy().allowedTools(), requiredMaterialPaths);
                boolean hasDependencyEvidence = projectContext != null
                        && runtimeRequest.inheritedTrustedEvidence().evidence().stream()
                        .anyMatch(ref -> ProjectMaterialScope.contains(requiredMaterialPaths, ref.file()));
                boolean stepCanCallTools = !runtimeRequest.toolPolicy().allowedTools().isEmpty();
                if (projectContext != null && projectEvidenceRefs.isEmpty()
                        && stepCanCallTools && !hasDependencyEvidence) {
                    String error = "INSUFFICIENT_EVIDENCE: Project step completed without a current authorized file observation.";
                    previousError = error;
                    if (attempt + 1 >= MAX_STEP_ATTEMPTS) {
                        step.markFailed(error, content);
                        recordEvent(plan.getId(), step.getId(), "step_evidence_insufficient", Map.of(
                                "stepKey", step.getStepKey(), "projectId", projectContext.projectId(), "traceId", traceId));
                        return;
                    }
                    continue;
                }
                if (projectContext != null && projectEvidenceRefs.isEmpty() && hasDependencyEvidence) {
                    recordEvent(plan.getId(), step.getId(), "step_dependency_evidence_reused", Map.of(
                            "stepKey", step.getStepKey(),
                            "projectId", projectContext.projectId(),
                            "dependencies", readStringList(step.getDependenciesJson()),
                            "traceId", traceId
                    ));
                }
                if (!projectEvidenceRefs.isEmpty()) {
                    content += "\n[projectEvidenceRefs=" + String.join(",", projectEvidenceRefs) + "]";
                    recordEvent(plan.getId(), step.getId(), "step_project_evidence", Map.of(
                            "stepKey", step.getStepKey(), "projectId", projectContext.projectId(),
                            "evidenceRefs", projectEvidenceRefs, "evidence", typedEvidence, "traceId", traceId));
                }
                boolean controlledPartial = result.runtimeStopSignal() != AgentRuntimeStopSignal.NONE
                        || "PARTIAL".equalsIgnoreCase(result.outcome());
                String controlledLimitation = controlledPartial
                        ? (StringUtils.hasText(result.errorMessage())
                        ? result.errorMessage()
                        : result.fallbacks().stream().reduce((first, second) -> second)
                        .orElse("Runtime stopped after preserving available evidence."))
                        : null;
                if (controlledPartial) {
                    recordEvent(plan.getId(), step.getId(), "step_controlled_stop_ready_for_verification", Map.of(
                            "stepKey", step.getStepKey(),
                            "result", abbreviate(content, 1200),
                            "reason", abbreviate(controlledLimitation, 1200),
                            "stopSignal", result.runtimeStopSignal().name(),
                            "traceId", traceId
                    ));
                }
                PlanStepVerifier.VerificationResult verification = stepVerifier.verify(new PlanStepVerifier.VerificationRequest(
                        plan,
                        session,
                        step,
                        allSteps,
                        content,
                        verifierExecutionFacts(result),
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
                    step.markDegraded(content, "VERIFIER_INCONCLUSIVE: "
                            + blankToDefault(verification.reason(), "Verifier could not make a reliable decision."));
                    recordEvent(plan.getId(), step.getId(), "step_completed_unverified", Map.of(
                            "stepKey", step.getStepKey(),
                            "result", abbreviate(content, 1200),
                            "reason", blankToDefault(verification.reason(), "Verifier could not make a reliable decision."),
                            "traceId", traceId
                    ));
                    return;
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
                    boolean hasTrustedProjectEvidence = projectContext != null
                            && (hasDependencyEvidence || !typedEvidence.isEmpty());
                    if (controlledPartial || hasTrustedProjectEvidence) {
                        step.markDegraded(content, "VERIFICATION_PARTIAL: " + abbreviate(error, 1200));
                        recordEvent(plan.getId(), step.getId(), "step_completed_unverified", Map.of(
                                "stepKey", step.getStepKey(),
                                "result", abbreviate(content, 1200),
                                "reason", abbreviate(error, 1200),
                                "traceId", traceId
                        ));
                        return;
                    }
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
                if (controlledPartial) {
                    step.markDegraded(content, "RUNTIME_PARTIAL: " + abbreviate(controlledLimitation, 1200));
                    recordEvent(plan.getId(), step.getId(), "step_degraded_after_controlled_stop", Map.of(
                            "stepKey", step.getStepKey(),
                            "steps", result.steps(),
                            "result", abbreviate(content, 1200),
                            "reason", abbreviate(controlledLimitation, 1200),
                            "controlledStop", true,
                            "traceId", traceId
                    ));
                    return;
                }
                String dependencyLimitation = degradedDependencyLimitation(allSteps, step);
                boolean newTrustedEvidence = hasNewTrustedEvidence(
                        result.trustedEvidenceLedger(), runtimeRequest.inheritedTrustedEvidence(), requiredMaterialPaths);
                if (StringUtils.hasText(dependencyLimitation)
                        && (runtimeRequest.toolPolicy().allowedTools().isEmpty()
                        || (projectContext != null && !newTrustedEvidence))) {
                    step.markDegraded(content, "DEPENDENCY_PARTIAL: " + abbreviate(dependencyLimitation, 1200));
                    recordEvent(plan.getId(), step.getId(), "step_degraded_from_dependency", Map.of(
                            "stepKey", step.getStepKey(),
                            "result", abbreviate(content, 1200),
                            "reason", abbreviate(dependencyLimitation, 1200),
                            "traceId", traceId
                    ));
                    return;
                }
                step.markCompleted(content);
                recordEvent(plan.getId(), step.getId(), "step_completed", Map.of(
                        "stepKey", step.getStepKey(),
                        "steps", result.steps(),
                        "result", abbreviate(content, 1200),
                        "controlledStop", false,
                        "traceId", traceId
                ));
                return;
            }

            String error = StringUtils.hasText(result.errorMessage()) ? result.errorMessage() : "Step execution failed.";
            previousError = error;
            recordRuntimeGuardrailEvent(plan, step, error, traceId);
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

    private boolean isControlledPlanStep(AgentPlan plan, AgentPlanStep step,
                                         AgentRuntimeRequest controlledParentRequest) {
        if (plan == null || step == null || controlledParentRequest == null
                || controlledParentRequest.controlledWorkerDispatch() == null
                || !ControlledPlanDispatchEnvelope.STEP_KEY.equals(step.getStepKey())) {
            return false;
        }
        JsonNode envelope = ProjectPlanEnvelope.restoreControlled(objectMapper, plan.getRawPlanJson(), plan.getUserId());
        if (envelope == null) return false;
        validateControlledPlanBinding(plan, envelope, controlledParentRequest.controlledWorkerDispatch());
        return true;
    }

    private boolean hasControlledEnvelopeMarker(AgentPlanStep step) {
        return step != null && StringUtils.hasText(step.getSuccessCriteria())
                && step.getSuccessCriteria().contains(ControlledPlanDispatchEnvelope.DIGEST_PREFIX);
    }

    private void executeControlledPlanStep(AgentPlan plan,
                                           List<AgentPlanStep> allSteps,
                                           AgentPlanStep step,
                                           ProjectRuntimeContext projectContext,
                                           String traceId,
                                           int attempt,
                                           AgentRuntimeRequest parentRequest) {
        if (parentRequest == null || parentRequest.controlledWorkerDispatch() == null
                || controlledWorkerExecutor == null || projectContext == null) {
            String error = "Controlled Worker dispatch is unavailable for this persisted Plan.";
            step.markFailed(error);
            recordEvent(plan.getId(), step.getId(), "step_failed", Map.of(
                    "stepKey", step.getStepKey(), "error", error, "traceId", traceId));
            return;
        }
        step.markRunning();
        steps.saveAndFlush(step);
        ProjectRuntimeContext currentContext = revalidateProject(plan.getUserId(), projectContext.projectId());
        EvidenceLedger inherited = new ProjectEvidenceValidator(projectService).current(
                plan.getUserId(), currentContext, dependencyEvidence(plan.getId(), allSteps, step));
        AgentRuntimeRequest executionRequest = parentRequest.withPlanId(plan.getId())
                .withProjectContext(currentContext)
                .withInheritedTrustedEvidence(inherited);
        AgentRuntimeResult result;
        try {
            result = controlledWorkerExecutor.executeWithinPlan(executionRequest);
        } catch (Exception exception) {
            String error = "Controlled Worker execution failed: "
                    + abbreviate(blankToDefault(exception.getMessage(), exception.getClass().getSimpleName()), 1200);
            result = new AgentRuntimeResult(false, null, List.of(), 0, error,
                    List.of(), List.of(error), null, null, null);
        }
        recordToolObservations(plan, step, result, currentContext, traceId, attempt);

        Set<String> assignedPaths = parentRequest.controlledWorkerDispatch().tasks().stream()
                .flatMap(task -> task.attestation().packet().materialScope().stream())
                .map(path -> ProjectMaterialScope.normalize(path.value()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        EvidenceLedger currentControlled = new ProjectEvidenceValidator(projectService).current(
                plan.getUserId(), currentContext, result.trustedEvidenceLedger(), true);
        List<EvidenceRef> persistedEvidence = currentControlled.evidence().stream()
                .filter(ref -> ProjectEvidenceValidator.isControlledWorkerEvidence(ref, currentContext.projectId()))
                .filter(ref -> ProjectMaterialScope.contains(assignedPaths, ref.file()))
                .map(ref -> persistedStepEvidence(ref, currentContext, plan, step, attempt))
                .toList();
        List<String> evidenceIds = persistedEvidence.stream().map(EvidenceRef::id).toList();
        String content = StringUtils.hasText(result.assistantContent())
                ? result.assistantContent() : "Controlled read-only Worker execution produced no synthesis.";
        if (!evidenceIds.isEmpty()) {
            content += "\n[projectEvidenceRefs=" + String.join(",", evidenceIds) + "]";
            recordEvent(plan.getId(), step.getId(), "step_project_evidence", Map.of(
                    "stepKey", step.getStepKey(), "projectId", currentContext.projectId(),
                    "evidenceRefs", evidenceIds, "evidence", persistedEvidence, "traceId", traceId));
        }
        if (!result.success()) {
            String error = blankToDefault(result.errorMessage(), "Controlled Worker execution failed.");
            step.markFailed(error, StringUtils.hasText(result.assistantContent()) ? content : null);
            recordEvent(plan.getId(), step.getId(), "step_failed", Map.of(
                    "stepKey", step.getStepKey(), "error", abbreviate(error, 1200), "traceId", traceId));
            return;
        }
        String limitation = result.fallbacks().stream().reduce((first, second) -> second)
                .orElse("Cross-material semantics remain unresolved without a deterministic trusted rule.");
        step.markDegraded(content, "CONTROLLED_WORKER_PARTIAL: " + abbreviate(limitation, 1200));
        recordEvent(plan.getId(), step.getId(), "step_degraded_after_controlled_stop", Map.of(
                "stepKey", step.getStepKey(), "steps", result.steps(),
                "result", abbreviate(content, 1200), "reason", abbreviate(limitation, 1200),
                "stopSignal", result.runtimeStopSignal().name(), "controlledWorker", true,
                "traceId", traceId));
    }

    private void executeStepBatch(AgentPlan plan,
                                  AgentSession session,
                                  List<AgentPlanStep> allSteps,
                                  List<AgentPlanStep> batch,
                                  ResolvedSkill skill,
                                  ProjectRuntimeContext projectContext,
                                  String projectManifestSummary,
                                  Map<String, String> sharedExecutionState,
                                  LocalDateTime deadlineAt,
                                  String traceId,
                                  AgentRuntimeRequest controlledParentRequest) {
        if (batch.isEmpty()) {
            return;
        }
        if (batch.size() == 1) {
            executeStepSafely(plan, session, allSteps, batch.get(0), skill, projectContext,
                    projectManifestSummary, sharedExecutionState, deadlineAt, traceId, controlledParentRequest);
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
                        () -> executeStepSafely(plan, session, allSteps, step, skill, projectContext,
                                projectManifestSummary, sharedExecutionState, deadlineAt, traceId,
                                controlledParentRequest))));
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
                                   ProjectRuntimeContext projectContext,
                                   String projectManifestSummary,
                                   Map<String, String> sharedExecutionState,
                                   LocalDateTime deadlineAt,
                                   String traceId,
                                   AgentRuntimeRequest controlledParentRequest) {
        try {
            executeStep(plan, session, allSteps, step, skill, projectContext, projectManifestSummary,
                    sharedExecutionState, deadlineAt, traceId, controlledParentRequest);
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

    private int maxRuntimeStepsForPlanStep(AgentSession session,
                                           ResolvedToolPolicy runtimeToolPolicy,
                                           boolean projectStep) {
        int configured = session.getMaxSteps() == null
                ? UserSettingsService.DEFAULT_MAX_STEPS : session.getMaxSteps();
        int effective = Math.max(1, configured);
        if (projectStep && runtimeToolPolicy != null) {
            log.info("Project Plan step runtime budget configuredSteps={} effectiveSteps={} "
                            + "maxToolCalls={} synthesisReserve={}",
                    configured, effective, runtimeToolPolicy.maxToolCalls(), 1);
        }
        return effective;
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
                                        ResolvedSkill skill,
                                        ProjectRuntimeContext projectContext) {
        if (failedStep.getStepKey().startsWith(REPAIR_STEP_PREFIX)) {
            return false;
        }
        ResolvedToolPolicy repairToolPolicy = resolveRepairToolPolicy(
                plan, allSteps, skill, projectContext);
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
                    repairToolPolicy.allowedTools()
            );
        } catch (Exception ex) {
            recordEvent(plan.getId(), failedStep.getId(), "step_repair_failed", Map.of(
                    "stepKey", failedStep.getStepKey(),
                    "error", abbreviate(blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 1200)
            ));
            return false;
        }
        if (repairSpec == null || !repairSpec.executable()) {
            recordEvent(plan.getId(), failedStep.getId(), "step_repair_unavailable", Map.of(
                    "stepKey", failedStep.getStepKey(),
                    "failureCode", repairSpec == null || repairSpec.failureCode() == null
                            ? "NO_EXECUTABLE_RECOVERY" : repairSpec.failureCode().name(),
                    "error", abbreviate(repairSpec == null
                            ? "Recovery planner returned no plan."
                            : blankToDefault(repairSpec.failureMessage(), "Recovery plan is not executable."), 1200)
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
                    writeJson(resolvePersistedStepAllowedTools(
                            repairStep.allowedTools(), repairStep.title(), repairStep.description(),
                            repairStep.type(), repairStep.successCriteria(), dependencies,
                            repairToolPolicy, projectContext != null)),
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
        if (spec == null || !spec.executable()) {
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

    private boolean preserveBoundedFailureSynthesis(AgentPlan plan,
                                                     List<AgentPlanStep> allSteps,
                                                     AgentPlanStep failedStep) {
        List<AgentPlanStep> completed = allSteps.stream()
                .filter(step -> AgentPlanStepStatus.COMPLETED.name().equals(step.getStatus())
                        || AgentPlanStepStatus.DEGRADED.name().equals(step.getStatus()))
                .filter(step -> StringUtils.hasText(step.getResult()))
                .sorted(Comparator.comparing(AgentPlanStep::getSortOrder))
                .toList();
        if (completed.isEmpty()) return false;

        AgentPlanStep terminalSynthesis = allSteps.stream()
                .max(Comparator.comparing(AgentPlanStep::getSortOrder))
                .orElse(null);
        if (terminalSynthesis == null
                || !AgentPlanStepStatus.SKIPPED.name().equals(terminalSynthesis.getStatus())
                || !isFinalSynthesisStep(plan, terminalSynthesis)) return false;

        String failure = "Step " + failedStep.getStepKey() + " ("
                + blankToDefault(failedStep.getTitle(), "untitled") + ") failed: "
                + blankToDefault(failedStep.getErrorMessage(), "required evidence was unavailable.");
        StringBuilder bounded = new StringBuilder("Governed completion status: PARTIAL\n");
        if (isCrossMaterialPlan(plan, terminalSynthesis)) {
            bounded.append("Cross-material consistency: UNRESOLVED\n");
        }
        bounded.append("Scope: Required Plan work failed, so the available completed observations are retained "
                        + "without claiming a complete or verified final conclusion.\n\n")
                .append("## Failure boundary\n")
                .append(failure).append("\n\n")
                .append("## Retained completed observations\n");
        for (AgentPlanStep step : completed) {
            bounded.append("\n### ").append(blankToDefault(step.getTitle(), step.getStepKey()))
                    .append(" [").append(step.getStatus()).append("]\n")
                    .append(step.getResult().strip()).append("\n");
            if (AgentPlanStepStatus.DEGRADED.name().equals(step.getStatus())
                    && StringUtils.hasText(step.getErrorMessage())) {
                bounded.append("Limitation: ").append(step.getErrorMessage().strip()).append("\n");
            }
        }
        bounded.append("\n## Unavailable conclusion\n")
                .append("The skipped final synthesis cannot establish the missing material findings or upgrade "
                        + "the retained observations to VERIFIED. Resolve the failed step and rerun the bounded "
                        + "synthesis to obtain a complete conclusion.");

        String warning = "DEPENDENCY_PARTIAL: " + failure;
        terminalSynthesis.markDegraded(bounded.toString(), warning);
        steps.saveAndFlush(terminalSynthesis);
        recordEvent(plan.getId(), terminalSynthesis.getId(), "step_degraded_after_dependency_failure", Map.of(
                "stepKey", terminalSynthesis.getStepKey(),
                "failedStepKey", failedStep.getStepKey(),
                "reason", abbreviate(warning, 1200),
                "retainedStepKeys", completed.stream().map(AgentPlanStep::getStepKey).toList()
        ));
        return true;
    }

    /**
     * A deterministically missing requested material cannot be replaced by model-selected neighboring files.
     * Produce the terminal bounded answer on the server so the final synthesis cannot turn unrelated symbols,
     * manifest entries or filenames into cross-material findings.
     */
    private boolean preserveMissingMaterialDependencySynthesis(AgentPlan plan,
                                                               List<AgentPlanStep> allSteps,
                                                               AgentPlanStep synthesis) {
        if (!isFinalSynthesisStep(plan, synthesis)) return false;
        List<AgentPlanStep> dependencyClosure = completedDependencyClosure(allSteps, synthesis);
        List<AgentPlanStep> missing = dependencyClosure.stream()
                .filter(step -> AgentPlanStepStatus.DEGRADED.name().equals(step.getStatus()))
                .filter(step -> StringUtils.hasText(step.getErrorMessage()))
                .filter(step -> step.getErrorMessage().startsWith(ProjectMaterialScope.MISSING_TARGET_PREFIX))
                .toList();
        if (missing.isEmpty()) return false;

        synthesis.markRunning();
        steps.saveAndFlush(synthesis);
        List<String> missingTargets = missing.stream()
                .map(AgentPlanStep::getErrorMessage)
                .map(value -> value.substring(ProjectMaterialScope.MISSING_TARGET_PREFIX.length()).trim())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        List<AgentPlanStep> retained = dependencyClosure.stream()
                .filter(step -> !missing.contains(step))
                .filter(step -> AgentPlanStepStatus.COMPLETED.name().equals(step.getStatus()))
                .filter(step -> StringUtils.hasText(step.getResult()))
                .toList();

        String targetList = missingTargets.isEmpty() ? "the explicitly requested Project material"
                : String.join(", ", missingTargets);
        StringBuilder bounded = new StringBuilder("# 交叉核对与综合结论\n\n")
                .append("指定材料 `").append(targetList).append("` 不存在，无法取得该材料的受信文件内容证据。")
                .append("以下仅保留已经完成的其他材料结果，不使用项目中的其他文件替代缺失材料。\n\n")
                .append("## 已保留的材料结果\n");
        if (retained.isEmpty()) {
            bounded.append("没有可保留的已完成材料结果。\n");
        } else {
            for (AgentPlanStep dependency : retained) {
                bounded.append("\n### ").append(blankToDefault(dependency.getTitle(), dependency.getStepKey()))
                        .append("\n")
                        .append(abbreviate(dependency.getResult().strip(), 6000)).append("\n");
            }
        }
        bounded.append("\n## 一致点\n")
                .append("无法判定。缺失材料没有当前受信内容证据，不能根据其他文件、类名、文件名或项目清单推断一致性。\n\n")
                .append("## 差异点\n")
                .append("无法判定。未读取到缺失材料的实际内容，不能形成材料间差异结论。\n\n")
                .append("## 证据位置\n")
                .append("- 已完成材料的证据保留在上方对应步骤结果中。\n")
                .append("- 缺失材料：`").append(targetList).append("`；服务器读取结果为 NOT_FOUND。\n\n")
                .append("## 待确认事项\n")
                .append("请确认缺失材料的正确相对路径，或将该文件加入当前 ProjectVersion 后重新执行。\n\n")
                .append("## 综合结论\n")
                .append("当前只能得到 PARTIAL、UNRESOLVED 的有界结果；不能验证材料间一致或不一致，也不能升级为 VERIFIED。");

        String warning = "DEPENDENCY_PARTIAL: required Project material missing: " + targetList;
        synthesis.markDegraded(bounded.toString(), warning);
        steps.saveAndFlush(synthesis);
        recordEvent(plan.getId(), synthesis.getId(), "step_degraded_missing_material_dependency", Map.of(
                "stepKey", synthesis.getStepKey(),
                "missingStepKeys", missing.stream().map(AgentPlanStep::getStepKey).toList(),
                "missingTargets", missingTargets,
                "retainedStepKeys", retained.stream().map(AgentPlanStep::getStepKey).toList(),
                "reason", warning
        ));
        return true;
    }

    private boolean isCrossMaterialPlan(AgentPlan plan, AgentPlanStep synthesis) {
        String semantics = String.join(" ",
                plan == null ? "" : blankToDefault(plan.getGoal(), ""),
                synthesis == null ? "" : blankToDefault(synthesis.getTitle(), ""),
                synthesis == null ? "" : blankToDefault(synthesis.getDescription(), ""))
                .toLowerCase(Locale.ROOT);
        return containsAny(semantics, "cross-material", "cross check", "cross-check", "consistency",
                "联合分析", "交叉核对", "一致性");
    }

    private List<String> resolvePersistedAllowedTools(List<String> stepAllowedTools, ResolvedToolPolicy inheritedPolicy) {
        if (stepAllowedTools == null) {
            return inheritedPolicy.allowedTools();
        }
        List<String> normalized = List.copyOf(stepAllowedTools);
        if (normalized.isEmpty()) {
            return List.of();
        }
        Set<String> intersection = new LinkedHashSet<>(normalized);
        intersection.retainAll(inheritedPolicy.allowedTools());
        return List.copyOf(intersection);
    }

    /**
     * The planner describes step semantics and may suggest tools, but it is not the tool-policy authority.
     * For Project acquisition steps the server adds the matching read-only research capability bundle and
     * then intersects the result with the already resolved Plan policy ceiling. Dependency-only synthesis
     * steps that explicitly request no tools remain tool-free.
     */
    private List<String> resolvePersistedStepAllowedTools(PlanningAgentPlanner.StepSpec step,
                                                          ResolvedToolPolicy inheritedPolicy,
                                                          boolean projectPlan) {
        return resolvePersistedStepAllowedTools(
                step.allowedTools(), step.title(), step.description(), step.type(), step.successCriteria(),
                step.dependencies(), inheritedPolicy, projectPlan);
    }

    private List<String> resolvePersistedStepAllowedTools(List<String> plannerTools,
                                                          String title,
                                                          String description,
                                                          String type,
                                                          String successCriteria,
                                                          List<String> dependencies,
                                                          ResolvedToolPolicy inheritedPolicy,
                                                          boolean projectPlan) {
        if (!projectPlan) {
            return resolvePersistedAllowedTools(plannerTools, inheritedPolicy);
        }
        boolean hasDependencies = dependencies != null && !dependencies.isEmpty();
        if (hasDependencies && plannerTools != null && plannerTools.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> requested = new LinkedHashSet<>();
        if (plannerTools != null) requested.addAll(plannerTools);
        String semantics = String.join(" ",
                blankToDefault(title, ""), blankToDefault(description, ""),
                blankToDefault(type, ""), blankToDefault(successCriteria, "")).toLowerCase(Locale.ROOT);
        boolean acquisition = !hasDependencies || !requested.isEmpty()
                || containsAny(semantics, "read", "search", "inspect", "extract", "analy", "audit",
                "读取", "检索", "搜索", "提取", "分析", "审查", "核对");
        if (acquisition) {
            if (containsAny(semantics, "code", "source code", "implementation", ".py", ".java", ".ts", ".js",
                    "代码", "源码", "程序实现")) {
                requested.add("project_code_symbols");
                requested.add("project_search");
                requested.add("project_read_file");
            }
            if (containsAny(semantics, "paper", "latex", ".tex", "manuscript", "论文", "算法假设")) {
                requested.add("project_latex_outline");
                requested.add("project_search");
                requested.add("project_read_file");
            }
            if (containsAny(semantics, "experiment", "experimental result", "config", "benchmark", "metric",
                    ".yaml", ".yml", "实验", "实验结果", "实验配置", "指标")) {
                requested.add("project_experiment_summary");
                requested.add("project_search");
                requested.add("project_read_file");
            }
            if (containsAny(semantics, "bibtex", ".bib", "citation", "bibliography", "references section",
                    "参考文献", "引文")) {
                requested.add("project_bibtex_audit");
                requested.add("project_search");
                requested.add("project_read_file");
            }
            if (containsAny(semantics, "manifest", "inventory", "文件清单", "项目目录")) {
                requested.add("project_manifest");
            }
        }
        List<String> resolved = inheritedPolicy.allowedTools().stream()
                .filter(requested::contains)
                .toList();
        log.info("Project Plan step tools resolved plannerTools={} resolvedTools={} dependencies={} semantics={}",
                plannerTools, resolved, dependencies, abbreviate(semantics, 240));
        return resolved;
    }

    private boolean containsAny(String value, String... candidates) {
        if (!StringUtils.hasText(value) || candidates == null) return false;
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && value.contains(candidate.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String degradedDependencyLimitation(List<AgentPlanStep> allSteps, AgentPlanStep currentStep) {
        if (allSteps == null || currentStep == null) return null;
        Map<String, AgentPlanStep> byKey = allSteps.stream()
                .filter(java.util.Objects::nonNull)
                .filter(item -> StringUtils.hasText(item.getStepKey()))
                .collect(java.util.stream.Collectors.toMap(
                        AgentPlanStep::getStepKey, item -> item, (left, right) -> left, LinkedHashMap::new));
        LinkedHashSet<String> pending = new LinkedHashSet<>(readStringList(currentStep.getDependenciesJson()));
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        List<String> degraded = new ArrayList<>();
        while (!pending.isEmpty()) {
            String key = pending.iterator().next();
            pending.remove(key);
            if (!visited.add(key) || java.util.Objects.equals(key, currentStep.getStepKey())) continue;
            AgentPlanStep dependency = byKey.get(key);
            if (dependency == null) continue;
            if (AgentPlanStepStatus.DEGRADED.name().equals(dependency.getStatus())) {
                degraded.add(key + (StringUtils.hasText(dependency.getErrorMessage())
                        ? " (" + abbreviate(dependency.getErrorMessage(), 300) + ")" : ""));
            }
            readStringList(dependency.getDependenciesJson()).stream()
                    .filter(parent -> !visited.contains(parent))
                    .forEach(pending::add);
        }
        return degraded.isEmpty() ? null
                : "Final synthesis inherited degraded dependency result(s): " + String.join(", ", degraded);
    }

    private boolean hasNewTrustedEvidence(EvidenceLedger resultEvidence, EvidenceLedger inheritedEvidence,
                                          Set<String> requiredMaterialPaths) {
        if (resultEvidence == null || resultEvidence.evidence().isEmpty()) return false;
        Set<String> inheritedIds = inheritedEvidence == null ? Set.of() : inheritedEvidence.evidence().stream()
                .map(EvidenceRef::id)
                .collect(java.util.stream.Collectors.toSet());
        return resultEvidence.evidence().stream()
                .anyMatch(ref -> ref != null && !inheritedIds.contains(ref.id())
                        && ProjectEvidenceValidator.isTrusted(ref)
                        && ProjectMaterialScope.contains(requiredMaterialPaths, ref.file()));
    }

    private Set<String> requiredProjectMaterialPaths(AgentPlanStep step) {
        if (step == null) return Set.of();
        return ProjectMaterialScope.explicitRelativePaths(
                step.getTitle(), step.getDescription(), step.getSuccessCriteria());
    }

    private String missingTargetProjectFile(AgentRuntimeResult result, Set<String> requiredMaterialPaths) {
        if (result == null || requiredMaterialPaths == null || requiredMaterialPaths.isEmpty()) return null;
        for (String fallback : result.fallbacks()) {
            if (!StringUtils.hasText(fallback)
                    || !fallback.startsWith(ProjectMaterialScope.MISSING_TARGET_PREFIX)) continue;
            String path = fallback.substring(ProjectMaterialScope.MISSING_TARGET_PREFIX.length()).trim();
            if (ProjectMaterialScope.contains(requiredMaterialPaths, path)) return path;
        }
        for (String trace : result.toolTrace()) {
            if (!StringUtils.hasText(trace) || !trace.contains("tool=project_read_file")
                    || !trace.contains("success=false")) continue;
            String normalized = trace.toLowerCase(Locale.ROOT);
            if (!(normalized.contains("not_found") || normalized.contains("not found")
                    || normalized.contains("404"))) continue;
            for (String path : requiredMaterialPaths) {
                if (normalized.replace('\\', '/').contains(path)) return path;
            }
        }
        return null;
    }

    private ResolvedToolPolicy resolveRuntimeToolPolicy(AgentPlanStep step, AgentPlan plan, ResolvedSkill skill,
                                                         ProjectRuntimeContext projectContext) {
        ResolvedToolPolicy inheritedPolicy = projectContext == null ? resolvePlanToolPolicy(
                plan.getGoal(), Boolean.TRUE.equals(plan.getRagDisabled()), skill)
                : toolPolicyEngine.decideProject(skill == null ? null : skill.allowedTools(), null).resolved();
        if (projectContext != null) {
            List<String> stepAllowed = readStringList(step.getAllowedToolsJson());
            List<String> allowed = StringUtils.hasText(step.getAllowedToolsJson())
                    ? resolvePersistedAllowedTools(stepAllowed, inheritedPolicy)
                    : inheritedPolicy.allowedTools();
            return new ResolvedToolPolicy(allowed, inheritedPolicy.maxToolCalls(),
                    Math.min(inheritedPolicy.maxDuplicateToolCalls(), MAX_DUPLICATE_TOOL_CALLS_PER_PLAN_STEP),
                    "project_plan_low_risk_read_only_tools");
        }
        List<String> stepAllowed = readStringList(step.getAllowedToolsJson());
        if (StringUtils.hasText(step.getAllowedToolsJson())) {
            return new ResolvedToolPolicy(resolvePersistedAllowedTools(stepAllowed, inheritedPolicy),
                    inheritedPolicy.maxToolCalls(),
                    Math.min(inheritedPolicy.maxDuplicateToolCalls(), MAX_DUPLICATE_TOOL_CALLS_PER_PLAN_STEP),
                    "plan_step_persisted_allowlist");
        }
        return inheritedPolicy;
    }

    /** A recovery plan may reuse, but never widen, the persisted Plan's original tool ceiling. */
    private ResolvedToolPolicy resolveRepairToolPolicy(AgentPlan plan,
                                                       List<AgentPlanStep> allSteps,
                                                       ResolvedSkill skill,
                                                       ProjectRuntimeContext projectContext) {
        ResolvedToolPolicy currentPolicy = projectContext == null
                ? resolvePlanToolPolicy(plan.getGoal(), Boolean.TRUE.equals(plan.getRagDisabled()), skill)
                : toolPolicyEngine.decideProject(skill == null ? null : skill.allowedTools(), null).resolved();
        if (allSteps == null || allSteps.stream()
                .anyMatch(step -> step == null || !StringUtils.hasText(step.getAllowedToolsJson()))) {
            return new ResolvedToolPolicy(List.of(), currentPolicy.maxToolCalls(),
                    currentPolicy.maxDuplicateToolCalls(), currentPolicy.reason() + "+unknown_plan_ceiling_deny_all");
        }
        LinkedHashSet<String> persistedCeiling = new LinkedHashSet<>();
        allSteps.stream().map(AgentPlanStep::getAllowedToolsJson)
                .map(this::readStringList)
                .forEach(persistedCeiling::addAll);
        List<String> allowed = currentPolicy.allowedTools().stream()
                .filter(persistedCeiling::contains)
                .toList();
        return new ResolvedToolPolicy(allowed, currentPolicy.maxToolCalls(),
                currentPolicy.maxDuplicateToolCalls(), currentPolicy.reason() + "+persisted_plan_ceiling");
    }

    private ResolvedToolPolicy resolvePlanToolPolicy(String userMessage, boolean ragDisabled, ResolvedSkill skill) {
        AgentToolPolicyEngine.Decision decision = toolPolicyEngine.decide(
                userMessage, ragDisabled, skill == null ? null : skill.allowedTools());
        return new ResolvedToolPolicy(decision.allowedTools(), decision.maxToolCalls(),
                Math.min(decision.maxDuplicateToolCalls(), MAX_DUPLICATE_TOOL_CALLS_PER_PLAN_STEP),
                "plan_" + decision.reason());
    }

    private String buildStepSystemPrompt(AgentPlan plan,
                                         List<AgentPlanStep> allSteps,
                                         AgentPlanStep step,
                                         String previousError,
                                         String executionStateSummary,
                                         String projectManifestSummary,
                                         Map<String, String> sharedExecutionState,
                                         List<String> resolvedAllowedTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an isolated worker sub-agent for a Plan-and-Execute system.
                Complete only the current step. Do not jump to later steps.
                Use real tool calls only when needed. Do not emit fake tool-call markup.
                Use tools sparingly: one or two focused searches are usually enough, then synthesize.
                Stop as soon as the current step success criteria are met.
                Return a concise reusable result for downstream steps.
                For a large source file, do not scan sequentially from the first line unless the user explicitly
                requests exhaustive full-file coverage. Use project_code_symbols to map the file, project_search
                to locate the named classes/functions/stages, then project_read_file only for targeted evidence ranges.
                Never claim that an entire file was read when server-observed ranges cover only part of it.
                When completed dependency results already cover the success criteria, synthesize them directly
                without new tools. Do not repeat their reads or searches. Call a tool only for a specific missing fact.
                If evidence is incomplete, state the limitation clearly instead of looping.

                Overall goal:
                """)
                .append(plan.getGoal()).append("\n\n")
                .append("Current step:\n")
                .append("- id: ").append(step.getStepKey()).append("\n")
                .append("- title: ").append(blankToDefault(step.getTitle(), "")).append("\n")
                .append("- type: ").append(blankToDefault(step.getType(), "")).append("\n")
                .append("- description: ").append(step.getDescription()).append("\n")
                .append("- success criteria: ").append(blankToDefault(step.getSuccessCriteria(), "")).append("\n")
                .append("- exact server-authorized tools: ")
                .append(resolvedAllowedTools == null ? List.of() : resolvedAllowedTools).append("\n")
                .append("  This is the complete server-enforced allowlist. If it is empty, do not request or imitate a tool call; synthesize from completed dependency results and state any remaining limitation.\n\n");
        if (isFinalSynthesisStep(plan, step)) {
            sb.append("This is the final synthesis step. Produce one compact, complete answer rather than a process recap. ")
                    .append("Preserve every section explicitly requested by the overall goal. For the current cross-material goal, ")
                    .append("use distinct sections for consistent points, differences, evidence locations, and items to confirm. ")
                    .append("If any dependency is DEGRADED, carry that limitation into the final answer and do not imply full completion. ")
                    .append("End with a complete sentence; do not end in an ellipsis or an unfinished table row.\n\n");
        }
        sb.append("Completed dependency results:\n");
        if (StringUtils.hasText(projectManifestSummary)) {
            sb.append("\nServer-cached Project manifest (reuse this inventory; do not call project_manifest again unless it is explicitly stale):\n")
                    .append(projectManifestSummary).append("\n\n");
        }
        boolean hasDependencyResult = false;
        for (AgentPlanStep item : completedDependencyClosure(allSteps, step)) {
            if (StringUtils.hasText(item.getResult())) {
                hasDependencyResult = true;
                sb.append("## ").append(item.getStepKey()).append(" ")
                        .append(blankToDefault(item.getTitle(), ""))
                        .append(" [").append(item.getStatus()).append("]\n");
                if (AgentPlanStepStatus.DEGRADED.name().equals(item.getStatus())
                        && StringUtils.hasText(item.getErrorMessage())) {
                    sb.append("Dependency limitation: ")
                            .append(abbreviate(item.getErrorMessage(), 800)).append("\n");
                }
                sb.append(abbreviate(item.getResult(), 3200)).append("\n\n");
                String dependencyState = sharedExecutionState.get(item.getStepKey());
                if (StringUtils.hasText(dependencyState)) {
                    sb.append("Reusable tool observations from ").append(item.getStepKey())
                            .append(" (including bounded results):\n")
                            .append(abbreviate(dependencyState, 3600))
                            .append("\nDo not repeat successful or zero-result searches; narrow or change the query only for a stated gap.\n\n");
                }
            }
        }
        if (!hasDependencyResult) {
            sb.append("None.\n");
        } else {
            sb.append("\nThe dependency results above are authoritative inputs for this step. Synthesize them first. ")
                    .append("Call a tool only after naming one success-criterion fact that is genuinely missing; ")
                    .append("never re-run a dependency tool merely to recapture its output.\n");
        }
        if (StringUtils.hasText(previousError)) {
            sb.append("\nPrevious attempt error:\n")
                    .append(abbreviate(previousError, 1200))
                    .append("\n");
        }
        if (StringUtils.hasText(executionStateSummary)) {
            sb.append("\nReusable observations from earlier attempts:\n")
                    .append(abbreviate(executionStateSummary, 2400))
                    .append("\nDo not repeat successful calls. Do not retry a deterministic failure with the same effective scope.\n");
        }
        return sb.toString();
    }

    private boolean isFinalSynthesisStep(AgentPlan plan, AgentPlanStep step) {
        if (step == null || readStringList(step.getDependenciesJson()).isEmpty()) return false;
        String semantics = String.join(" ", blankToDefault(step.getTitle(), ""),
                blankToDefault(step.getDescription(), ""), blankToDefault(step.getType(), ""),
                plan == null ? "" : blankToDefault(plan.getGoal(), "")).toLowerCase(Locale.ROOT);
        return containsAny(semantics, "synth", "final", "conclusion", "cross-material",
                "综合", "最终", "结论", "交叉核对");
    }

    private String buildPlanManifestSummary(Long userId, Long projectId) {
        ProjectManifestResponse manifest = projectService.manifest(userId, projectId);
        StringBuilder summary = new StringBuilder("version=")
                .append(blankToDefault(manifest.version(), "unknown"))
                .append(", files=").append(manifest.files().size()).append("\n");
        for (ProjectFileEntry file : manifest.files()) {
            String line = "- " + file.path() + " (" + file.sizeBytes() + " bytes)\n";
            if (summary.length() + line.length() > 6000) {
                summary.append("- ... additional files omitted from prompt; use a focused manifest call only if required.\n");
                break;
            }
            summary.append(line);
        }
        return summary.toString();
    }

    private String buildStepUserMessage(AgentPlanStep step) {
        return "Execute the current plan step:\n" + step.getDescription()
                + "\n\nSuccess criteria:\n" + blankToDefault(step.getSuccessCriteria(), "");
    }

    private String verifierExecutionFacts(AgentRuntimeResult result) {
        if (result == null || result.toolTrace() == null || result.toolTrace().isEmpty()) return "";
        StringBuilder facts = new StringBuilder();
        for (String trace : result.toolTrace()) {
            if (!StringUtils.hasText(trace)) continue;
            facts.append("- ").append(abbreviate(trace, 700)).append("\n");
            if (facts.length() >= 4000) break;
        }
        return abbreviate(facts.toString(), 4000);
    }

    private void recordToolObservations(AgentPlan plan,
                                        AgentPlanStep step,
                                        AgentRuntimeResult result,
                                        ProjectRuntimeContext projectContext,
                                        String traceId,
                                        int attempt) {
        if (result == null || result.toolTrace() == null) return;
        List<DomainRuntimeFacts.ToolOutcome> typedOutcomes = result.domainRuntimeFacts().toolOutcomes();
        if (!typedOutcomes.isEmpty()) {
            for (int index = 0; index < typedOutcomes.size(); index++) {
                DomainRuntimeFacts.ToolOutcome outcome = typedOutcomes.get(index);
                String trace = index < result.toolTrace().size() ? result.toolTrace().get(index) : "";
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("stepKey", step.getStepKey());
                payload.put("attempt", attempt);
                payload.put("toolName", outcome.toolName());
                payload.put("runtimeStep", outcome.runtimeStep());
                payload.put("executionAttempt", Math.max(0,
                        ((attempt - 1) * 2) + outcome.executionAttempt()));
                payload.put("executed", outcome.executed());
                payload.put("budgetConsumed", outcome.budgetConsumed());
                payload.put("success", outcome.success());
                payload.put("reused", outcome.reused());
                payload.put("skipped", outcome.skipped());
                if (StringUtils.hasText(trace)) {
                    payload.put("observationFingerprint", Integer.toHexString(trace.hashCode()));
                    payload.put("trace", abbreviate(trace, 1600));
                }
                payload.put("traceId", traceId);
                if (projectContext != null) payload.put("projectId", projectContext.projectId());
                recordEvent(plan.getId(), step.getId(), "step_tool_observation", payload);
            }
        } else {
            for (String trace : result.toolTrace()) {
                if (!StringUtils.hasText(trace) || !trace.contains(" tool=")) continue;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("stepKey", step.getStepKey());
                payload.put("attempt", attempt);
                payload.put("observationFingerprint", Integer.toHexString(trace.hashCode()));
                payload.put("success", trace.contains(" success=true"));
                payload.put("reused", trace.contains(" reused=true"));
                payload.put("trace", abbreviate(trace, 1600));
                payload.put("traceId", traceId);
                if (projectContext != null) payload.put("projectId", projectContext.projectId());
                recordEvent(plan.getId(), step.getId(), "step_tool_observation", payload);
            }
        }
        for (DomainRuntimeFacts.ConsistencyFact fact : result.domainRuntimeFacts().consistencyFacts()) {
            DomainRuntimeFacts.ConsistencyFact persistedFact = persistedConsistencyFact(
                    fact, result, projectContext, plan, step, attempt);
            if (persistedFact == null) continue;
            recordEvent(plan.getId(), step.getId(), "step_domain_consistency_fact", Map.of(
                    "stepKey", step.getStepKey(),
                    "fact", persistedFact,
                    "traceId", traceId
            ));
        }
        for (BoundedToolResult toolResult : boundedToolResults(result)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stepKey", step.getStepKey());
            payload.put("attempt", attempt);
            payload.put("toolName", toolResult.toolName());
            payload.put("toolCallId", toolResult.toolCallId());
            payload.put("result", toolResult.content());
            payload.put("traceId", traceId);
            if (projectContext != null) payload.put("projectId", projectContext.projectId());
            recordEvent(plan.getId(), step.getId(), "step_tool_result", payload);
        }
    }

    private String mergeExecutionState(String existing, AgentRuntimeResult result) {
        StringBuilder state = new StringBuilder(StringUtils.hasText(existing) ? existing.trim() + "\n" : "");
        if (result != null && result.toolTrace() != null) {
            for (String trace : result.toolTrace()) {
                if (!StringUtils.hasText(trace)) continue;
                state.append(trace.contains(" success=true") ? "- SUCCESS: " : "- FAILED: ")
                        .append(abbreviate(trace, 600)).append("\n");
            }
        }
        for (BoundedToolResult toolResult : boundedToolResults(result)) {
            state.append("- REUSABLE RESULT tool=").append(toolResult.toolName()).append(": ")
                    .append(toolResult.content()).append("\n");
        }
        if (result != null && StringUtils.hasText(result.assistantContent())) {
            state.append("- Candidate result already produced: ")
                    .append(abbreviate(result.assistantContent(), 800)).append("\n");
        }
        return abbreviate(state.toString(), 6000);
    }

    private Map<String, String> restoreExecutionState(Long planId) {
        Map<String, StringBuilder> restored = new LinkedHashMap<>();
        List<AgentPlanEvent> persisted = events.findByPlanIdOrderByCreatedAtAsc(planId);
        if (persisted == null) {
            return new ConcurrentHashMap<>();
        }
        for (AgentPlanEvent event : persisted) {
            if (!("step_tool_observation".equals(event.getEventType())
                    || "step_tool_result".equals(event.getEventType()))
                    || !StringUtils.hasText(event.getPayloadJson())) {
                continue;
            }
            try {
                JsonNode payload = objectMapper.readTree(event.getPayloadJson());
                String stepKey = payload.path("stepKey").asText("");
                if (!StringUtils.hasText(stepKey)) {
                    continue;
                }
                StringBuilder state = restored.computeIfAbsent(stepKey, ignored -> new StringBuilder());
                if ("step_tool_result".equals(event.getEventType())) {
                    String content = payload.path("result").asText("");
                    if (StringUtils.hasText(content)) {
                        state.append("- REUSABLE RESULT tool=").append(payload.path("toolName").asText("unknown"))
                                .append(": ").append(abbreviate(content, 2400)).append("\n");
                    }
                } else {
                    String trace = payload.path("trace").asText("");
                    if (StringUtils.hasText(trace)) {
                        String prefix = payload.path("success").asBoolean(false) ? "- SUCCESS: " : "- FAILED: ";
                        state.append(prefix).append(abbreviate(trace, 600)).append("\n");
                    }
                }
            } catch (Exception ignored) {
                // Malformed diagnostic events cannot become reusable execution state.
            }
        }
        Map<String, String> result = new ConcurrentHashMap<>();
        restored.forEach((key, value) -> result.put(key, abbreviate(value.toString(), 6000)));
        return result;
    }

    private List<BoundedToolResult> boundedToolResults(AgentRuntimeResult result) {
        if (result == null || result.messages() == null) return List.of();
        Map<String, String> toolNames = new LinkedHashMap<>();
        for (ChatMessage message : result.messages()) {
            if (message == null || message.toolCalls() == null) continue;
            for (ToolCall call : message.toolCalls()) {
                if (call != null && StringUtils.hasText(call.id()) && call.function() != null) {
                    toolNames.put(call.id(), blankToDefault(call.function().name(), "unknown"));
                }
            }
        }
        List<BoundedToolResult> bounded = new ArrayList<>();
        for (ChatMessage message : result.messages()) {
            if (message == null || !"tool".equals(message.role()) || !StringUtils.hasText(message.content())) continue;
            String callId = blankToDefault(message.toolCallId(), "unknown");
            bounded.add(new BoundedToolResult(callId, toolNames.getOrDefault(callId, "unknown"),
                    abbreviate(message.content(), 2400)));
        }
        return bounded;
    }

    private record BoundedToolResult(String toolCallId, String toolName, String content) {}

    /** Reads only trusted observations produced by completed/degraded ancestors in the persisted Plan DAG. */
    private EvidenceLedger dependencyEvidence(Long planId,
                                              List<AgentPlanStep> allSteps,
                                              AgentPlanStep step) {
        Set<Long> dependencyIds = completedDependencyClosure(allSteps, step).stream()
                .map(AgentPlanStep::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (dependencyIds.isEmpty()) {
            return EvidenceLedger.empty();
        }
        List<AgentPlanEvent> persisted = events.findByPlanIdOrderByCreatedAtAsc(planId);
        if (persisted == null) {
            return EvidenceLedger.empty();
        }
        Map<String, EvidenceRef> trusted = new LinkedHashMap<>();
        for (AgentPlanEvent event : persisted) {
            if (!dependencyIds.contains(event.getStepId())
                    || !"step_project_evidence".equals(event.getEventType())
                    || !StringUtils.hasText(event.getPayloadJson())) {
                continue;
            }
            try {
                JsonNode values = objectMapper.readTree(event.getPayloadJson()).path("evidence");
                if (!values.isArray()) {
                    continue;
                }
                for (JsonNode value : values) {
                    EvidenceRef ref = objectMapper.treeToValue(value, EvidenceRef.class);
                    if (ref != null && ProjectEvidenceValidator.isTrusted(ref)) {
                        EvidenceRef existing = trusted.putIfAbsent(ref.id(), ref);
                        if (existing != null && !existing.equals(ref)) {
                            throw new IllegalArgumentException("conflicting inherited evidence id: " + ref.id());
                        }
                    }
                }
            } catch (Exception ignored) {
                // Malformed events never satisfy a Project evidence dependency.
            }
        }
        return new EvidenceLedger(List.copyOf(trusted.values()));
    }

    /**
     * Resolves all usable ancestors, not only direct dependencies. A tool-free synthesis or
     * plan_repaired replacement must retain the evidence/results already established earlier in
     * the same DAG, while failed, skipped and merely superseded steps remain non-authoritative.
     */
    private List<AgentPlanStep> completedDependencyClosure(List<AgentPlanStep> allSteps,
                                                           AgentPlanStep step) {
        if (allSteps == null || allSteps.isEmpty() || step == null) {
            return List.of();
        }
        Map<String, AgentPlanStep> byKey = allSteps.stream()
                .filter(java.util.Objects::nonNull)
                .filter(item -> StringUtils.hasText(item.getStepKey()))
                .collect(java.util.stream.Collectors.toMap(
                        AgentPlanStep::getStepKey, item -> item, (left, right) -> left,
                        LinkedHashMap::new));
        LinkedHashSet<String> pending = new LinkedHashSet<>(readStringList(step.getDependenciesJson()));
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        List<AgentPlanStep> resolved = new ArrayList<>();
        while (!pending.isEmpty()) {
            String key = pending.iterator().next();
            pending.remove(key);
            if (!visited.add(key) || java.util.Objects.equals(step.getStepKey(), key)) {
                continue;
            }
            AgentPlanStep dependency = byKey.get(key);
            if (dependency == null || !isUsableDependency(dependency)) {
                continue;
            }
            resolved.add(dependency);
            for (String ancestor : readStringList(dependency.getDependenciesJson())) {
                if (!visited.contains(ancestor)) {
                    pending.add(ancestor);
                }
            }
        }
        return resolved.stream()
                .sorted(Comparator.comparing(AgentPlanStep::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private boolean isUsableDependency(AgentPlanStep step) {
        return AgentPlanStepStatus.COMPLETED.name().equals(step.getStatus())
                || AgentPlanStepStatus.DEGRADED.name().equals(step.getStatus());
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

    private void recordRuntimeGuardrailEvent(AgentPlan plan, AgentPlanStep step, String error, String traceId) {
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
                                        String traceId,
                                        boolean persistConversationSummary) {
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
        if (persistConversationSummary) {
            persistConversationSummary(userId, sessionId, plan, steps.findByPlanIdOrderBySortOrderAsc(plan.getId()));
        }
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

    private void validatePlanExecutionBoundary(AgentPlan plan, Long userId, String traceId) {
        restoreProjectContext(plan, userId);
        JsonNode controlled = ProjectPlanEnvelope.restoreControlled(objectMapper, plan.getRawPlanJson(), userId);
        boolean persistedControlledMarker = steps.findByPlanIdOrderBySortOrderAsc(plan.getId()).stream()
                .anyMatch(this::hasControlledEnvelopeMarker);
        if (controlled == null && persistedControlledMarker) {
            throw new IllegalStateException("Controlled Worker envelope is missing from its persisted Plan.");
        }
        if (controlled != null && restoreControlledExecutionRequest(plan, userId, traceId, true) == null) {
            throw new IllegalStateException("controlled Plan authority could not be recovered");
        }
    }

    private AgentRuntimeRequest restoreControlledExecutionRequest(AgentPlan plan, Long userId, String traceId,
                                                                   boolean persistConversationSummary) {
        JsonNode controlled = ProjectPlanEnvelope.restoreControlled(objectMapper, plan.getRawPlanJson(), userId);
        if (controlled == null) return null;
        ProjectRuntimeContext context = restoreProjectContext(plan, userId);
        if (context == null) {
            throw new IllegalStateException("controlled Plan is missing its Project context");
        }
        AgentSession session = agentService.getOwnedSession(userId, plan.getSessionId());
        ResolvedSkill skill = resolveSkill(userId, plan.getSkillId());
        UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                userId, session.getModelProviderSnapshot(), session.getModelSnapshot());
        ResolvedToolPolicy currentPolicy = toolPolicyEngine
                .decideProject(skill == null ? null : skill.allowedTools(), null).resolved();
        AgentRuntimeRequest base = new AgentRuntimeRequest(
                AgentStrategy.PLAN_EXECUTE, session.getId(), List.of(), userId, plan.getGoal(),
                endpoint.providerKey(), endpoint.modelName(),
                null, null, 1, Boolean.TRUE.equals(plan.getRagDisabled()), skill == null ? null : skill.id(),
                endpoint.apiKey(), endpoint.apiUrl(), skill == null ? null : skill.prompt(),
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, currentPolicy,
                currentPolicy.maxToolCalls(), currentPolicy.maxDuplicateToolCalls(), traceId, null, null,
                plan.getId(), context, EvidenceLedger.empty(), AgentOrchestrationRequirements.empty(),
                persistConversationSummary, null);
        ProjectManifestResponse manifest = projectService.manifest(userId, context.projectId());
        ControlledPlanDispatchEnvelope.Recovery recovery = ControlledPlanDispatchEnvelope.recover(
                objectMapper, controlled, base, manifest);
        AgentRuntimeRequest recovered = recovery.attach(base);
        validateControlledPlanBinding(plan, controlled, recovered.controlledWorkerDispatch());
        return recovered;
    }

    private void validateControlledPlanBinding(AgentPlan plan, JsonNode controlled,
                                               ControlledWorkerDispatch dispatch) {
        List<AgentPlanStep> persistedSteps = steps.findByPlanIdOrderBySortOrderAsc(plan.getId());
        if (persistedSteps.size() != 1) {
            throw new IllegalStateException("controlled Plan must contain exactly one persisted step");
        }
        AgentPlanStep step = persistedSteps.get(0);
        String marker = ControlledPlanDispatchEnvelope.digestMarker(objectMapper, controlled);
        if (!plan.getId().equals(step.getPlanId())
                || !ControlledPlanDispatchEnvelope.STEP_KEY.equals(step.getStepKey())
                || !StringUtils.hasText(step.getSuccessCriteria())
                || !step.getSuccessCriteria().endsWith(marker)
                || !readStringList(step.getDependenciesJson()).isEmpty()) {
            throw new IllegalStateException("controlled Plan step is not bound to its persisted envelope");
        }
        List<String> expectedTools = dispatch.tasks().stream()
                .flatMap(task -> task.attestation().packet().allowedReadTools().stream())
                .distinct().sorted().toList();
        List<String> persistedTools = readStringList(step.getAllowedToolsJson()).stream().distinct().sorted().toList();
        if (!persistedTools.equals(expectedTools)) {
            throw new IllegalStateException("controlled Plan step tools do not match its persisted envelope");
        }
    }

    private ProjectRuntimeContext restoreProjectContext(AgentPlan plan, Long userId) {
        ProjectRuntimeContext context = ProjectPlanEnvelope.restore(objectMapper, plan.getRawPlanJson(), userId);
        return context == null ? null : revalidateProject(userId, context.projectId());
    }

    /** Exposes only persisted trusted Plan evidence, with its currentness revalidated server-side. */
    @Transactional(readOnly = true)
    public List<ProjectEvidenceResponse> listProjectEvidence(Long userId, Long projectId, Long planId) {
        ProjectRuntimeContext requested = revalidateProject(userId, projectId);
        AgentPlan plan = getOwnedPlan(userId, planId);
        ProjectRuntimeContext bound = ProjectPlanEnvelope.restore(objectMapper, plan.getRawPlanJson(), userId);
        if (bound == null || !requested.projectId().equals(bound.projectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project Plan does not exist.");
        }
        var manifest = projectService.manifest(userId, projectId);
        return evidenceFromStepEvents(planId).evidence().stream()
                .filter(ref -> ref.sourceType() == EvidenceSourceType.PROJECT && ProjectEvidenceValidator.isTrusted(ref))
                .map(ref -> new ProjectEvidenceResponse(ref.id(), ref.file(), ref.version(), ref.version(), ref.chunk(), true,
                        manifest.files().stream().anyMatch(file -> file.path().equals(ref.file())
                                && file.sha256().equals(ref.version()))))
                .toList();
    }

    private ProjectRuntimeContext revalidateProject(Long userId, Long projectId) {
        if (projectService == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project Plan capability is unavailable.");
        }
        // manifest is deliberately re-read at each creation/execution/retry boundary; it enforces owner + READ_ONLY.
        projectService.manifest(userId, projectId);
        return new ProjectRuntimeContext(userId, projectId);
    }

    private List<String> extractProjectEvidenceRefs(AgentRuntimeResult result, ProjectRuntimeContext context,
                                                    List<String> allowedTools, Set<String> requiredMaterialPaths) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        Set<String> allowedResearchTools = ResearchProjectEvidenceAdapter.allowedResearchTools(allowedTools);
        ResearchProjectEvidenceAdapter.extract(objectMapper, result.messages(), 0, context, allowedResearchTools).evidence()
                .stream()
                .filter(ref -> ProjectMaterialScope.contains(requiredMaterialPaths, ref.file()))
                .forEach(ref -> refs.add(ref.id()));
        for (ChatMessage message : result.messages()) {
            if (message == null || !"tool".equals(message.role()) || !StringUtils.hasText(message.content())) continue;
            try {
                JsonNode node = objectMapper.readTree(message.content());
                if (node.path("projectId").asLong(-1) != context.projectId()) continue;
                String path = node.path("relativePath").asText("");
                if (!StringUtils.hasText(path) || "manifest".equals(path)) continue;
                if (!ProjectMaterialScope.contains(requiredMaterialPaths, path)) continue;
                JsonNode values = node.path("evidenceRefs");
                if (values.isArray()) values.forEach(value -> refs.add(value.asText()));
            } catch (Exception ignored) { }
        }
        return List.copyOf(refs);
    }

    /** Reads only server-persisted step evidence events, never model result prose. */
    private EvidenceLedger evidenceFromStepEvents(Long planId) {
        List<EvidenceRef> refs = new ArrayList<>();
        for (AgentPlanEvent event : events.findByPlanIdOrderByCreatedAtAsc(planId)) {
            if (!"step_project_evidence".equals(event.getEventType()) || !StringUtils.hasText(event.getPayloadJson())) continue;
            try {
                JsonNode values = objectMapper.readTree(event.getPayloadJson()).path("evidence");
                if (!values.isArray()) continue;
                for (JsonNode value : values) {
                    EvidenceRef ref = objectMapper.treeToValue(value, EvidenceRef.class);
                    if (ref != null && ProjectEvidenceValidator.isTrusted(ref)) refs.add(ref);
                }
            } catch (Exception ignored) {
                // A malformed event cannot manufacture completion evidence.
            }
        }
        return new EvidenceLedger(refs);
    }

    /** Reconstructs only typed server-owned Plan observations; legacy diagnostic text proves nothing. */
    private DomainRuntimeFacts domainRuntimeFacts(AgentPlanResponse plan) {
        if (plan == null || plan.id() == null) return DomainRuntimeFacts.empty();
        List<AgentPlanEvent> persisted = events.findByPlanIdOrderByCreatedAtAsc(plan.id());
        if (persisted == null) persisted = List.of();
        Set<Long> controlledStops = persisted.stream()
                .filter(event -> "step_degraded_after_controlled_stop".equals(event.getEventType())
                        || "step_completed_after_controlled_stop".equals(event.getEventType()))
                .map(AgentPlanEvent::getStepId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, String> replacements = new LinkedHashMap<>();
        for (AgentPlanEvent event : persisted) {
            if (!"plan_repaired".equals(event.getEventType()) || !StringUtils.hasText(event.getPayloadJson())) continue;
            try {
                JsonNode payload = objectMapper.readTree(event.getPayloadJson());
                String failedStepKey = payload.path("failedStepKey").asText(null);
                String replacementStepKey = payload.path("replacementStepKey").asText(null);
                if (StringUtils.hasText(failedStepKey) && StringUtils.hasText(replacementStepKey)) {
                    replacements.put(failedStepKey, replacementStepKey);
                }
            } catch (Exception ignored) {
                // A malformed repair event cannot authorize recovery of an older failed step.
            }
        }

        List<DomainRuntimeFacts.ToolOutcome> toolOutcomes = new ArrayList<>();
        List<DomainRuntimeFacts.ConsistencyFact> consistencyFacts = new ArrayList<>();
        for (AgentPlanEvent event : persisted) {
            if ("step_domain_consistency_fact".equals(event.getEventType())
                    && StringUtils.hasText(event.getPayloadJson())) {
                try {
                    JsonNode fact = objectMapper.readTree(event.getPayloadJson()).path("fact");
                    consistencyFacts.add(objectMapper.treeToValue(fact, DomainRuntimeFacts.ConsistencyFact.class));
                } catch (Exception ignored) {
                    // Malformed or unsupported facts cannot prove cross-material consistency.
                }
                continue;
            }
            if (!"step_tool_observation".equals(event.getEventType())
                    || !StringUtils.hasText(event.getPayloadJson())) continue;
            try {
                JsonNode payload = objectMapper.readTree(event.getPayloadJson());
                if (!payload.hasNonNull("toolName") || !payload.has("executed")
                        || !payload.has("budgetConsumed") || !payload.has("success")
                        || !payload.has("reused") || !payload.has("skipped")) continue;
                toolOutcomes.add(new DomainRuntimeFacts.ToolOutcome(
                        payload.path("toolName").asText(),
                        payload.path("runtimeStep").asInt(0),
                        payload.path("stepKey").asText(null),
                        payload.path("executed").asBoolean(false),
                        payload.path("budgetConsumed").asBoolean(false),
                        payload.path("success").asBoolean(false),
                        payload.path("reused").asBoolean(false),
                        payload.path("skipped").asBoolean(false),
                        payload.has("executionAttempt")
                                ? payload.path("executionAttempt").asInt(0)
                                : Math.max(0, (payload.path("attempt").asInt(1) - 1) * 2)
                ));
            } catch (Exception ignored) {
                // Malformed or legacy event payloads cannot become verification facts.
            }
        }

        List<DomainRuntimeFacts.PlanStepOutcome> stepOutcomes = (plan.steps() == null ? List.<AgentPlanStepResponse>of()
                : plan.steps()).stream()
                .map(step -> new DomainRuntimeFacts.PlanStepOutcome(
                        step.stepKey(), DomainRuntimeFacts.PlanStepStatus.from(step.status()),
                        step.id() != null && controlledStops.contains(step.id()), replacements.get(step.stepKey())))
                .toList();
        return new DomainRuntimeFacts(toolOutcomes, stepOutcomes, consistencyFacts);
    }

    private List<EvidenceRef> trustedStepEvidence(AgentRuntimeResult result, ProjectRuntimeContext context,
                                                  List<String> allowedTools, EvidenceLedger inherited,
                                                  AgentPlan plan, AgentPlanStep step, int attempt,
                                                  Set<String> requiredMaterialPaths) {
        Set<String> inheritedIds = inherited == null ? Set.of() : inherited.evidence().stream()
                .map(EvidenceRef::id).collect(java.util.stream.Collectors.toSet());
        List<EvidenceRef> trusted = result.trustedEvidenceLedger().evidence().stream()
                .filter(ProjectEvidenceValidator::isTrusted)
                .filter(ref -> !inheritedIds.contains(ref.id()))
                .filter(ref -> ProjectMaterialScope.contains(requiredMaterialPaths, ref.file()))
                .toList();
        if (!trusted.isEmpty()) {
            return trusted.stream().map(ref -> persistedStepEvidence(ref, context, plan, step, attempt)).toList();
        }
        Map<String, EvidenceRef> observed = new LinkedHashMap<>();
        // Preserve the legacy top-level Project tool path, but never let its adapter pass
        // reintroduce a research observation outside the step's exact allowed-tool set.
        AgentService.projectEvidenceFromRuntime(objectMapper, result, context, 0).evidence().stream()
                .filter(ref -> ref.id().startsWith("project-observation-"))
                .forEach(ref -> observed.putIfAbsent(ref.id(), ref));
        ResearchProjectEvidenceAdapter.extract(objectMapper, result.messages(), 0, context,
                        ResearchProjectEvidenceAdapter.allowedResearchTools(allowedTools)).evidence()
                .forEach(ref -> observed.putIfAbsent(ref.id(), ref));
        return observed.values().stream()
                .filter(ref -> ProjectMaterialScope.contains(requiredMaterialPaths, ref.file()))
                .map(ref -> persistedStepEvidence(ref, context, plan, step, attempt)).toList();
    }

    private EvidenceRef persistedStepEvidence(EvidenceRef ref, ProjectRuntimeContext context,
                                               AgentPlan plan, AgentPlanStep step, int attempt) {
        String identity = String.join("|",
                blankToDefault(ref.id(), ""), blankToDefault(ref.file(), ""), blankToDefault(ref.version(), ""),
                blankToDefault(ref.chunk(), ""), blankToDefault(ref.citation(), ""));
        String observationId = java.util.UUID.nameUUIDFromBytes(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return new EvidenceRef("trusted-plan:" + context.projectId() + ":" + plan.getId() + ":"
                + step.getId() + ":" + attempt + ":" + observationId,
                EvidenceSourceType.PROJECT, "PROJECT", ref.file(), ref.chunk(), ref.citation(), ref.version(),
                "persisted plan step project observation", ref.projectVersion(), ref.fileHash(), ref.startLine(),
                ref.endLine(), ref.parserVersion(), ref.versionStatus());
    }

    private DomainRuntimeFacts.ConsistencyFact persistedConsistencyFact(
            DomainRuntimeFacts.ConsistencyFact fact,
            AgentRuntimeResult result,
            ProjectRuntimeContext context,
            AgentPlan plan,
            AgentPlanStep step,
            int attempt) {
        if (fact == null || context == null || result == null) return null;
        Map<String, EvidenceRef> trustedById = result.trustedEvidenceLedger().evidence().stream()
                .filter(ProjectEvidenceValidator::isTrusted)
                .collect(java.util.stream.Collectors.toMap(EvidenceRef::id, ref -> ref, (left, right) -> left));
        List<String> persistedRefs = new ArrayList<>();
        for (String evidenceId : fact.evidenceRefs()) {
            EvidenceRef ref = trustedById.get(evidenceId);
            if (ref == null) return null;
            if (evidenceId.startsWith("trusted-plan:" + context.projectId() + ":")) {
                persistedRefs.add(evidenceId);
            } else if (evidenceId.startsWith("trusted-tool:" + context.projectId() + ":")) {
                persistedRefs.add(persistedStepEvidence(ref, context, plan, step, attempt).id());
            } else {
                return null;
            }
        }
        return new DomainRuntimeFacts.ConsistencyFact(fact.ruleId(), fact.materials(), persistedRefs,
                fact.consistent(), fact.source());
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
        String scope = planId == null ? "create" : planId.toString();
        return "plan-" + scope + "-" + UUID.randomUUID().toString().substring(0, 8);
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
