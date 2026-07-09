package com.yanban.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.memory.LongTermMemoryRetrievalService;
import com.yanban.api.observability.TraceIdFilter;
import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.ResolvedSkill;
import com.yanban.api.skills.SkillsService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionSummary;
import com.yanban.core.agent.AgentSessionSummaryService;
import com.yanban.core.agent.AgentSessionSummaryUpdate;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import com.yanban.core.user.UserAccountPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int GENERATED_TITLE_MAX_LENGTH = 40;
    private static final int DEFAULT_VISIBLE_MESSAGE_LIMIT = 50;
    private static final int MAX_VISIBLE_MESSAGE_LIMIT = 100;
    private static final int SESSION_SUMMARY_MAX_CHARACTERS = 4_000;
    private static final int SUMMARY_TURN_SNIPPET_MAX_CHARACTERS = 1_200;
    private static final int SESSION_FACT_MAX_COUNT = 24;
    private static final int SESSION_FACT_MAX_CHARACTERS = 320;
    private static final List<String> CHAT_VISIBLE_ROLES = List.of("user", "assistant");
    private static final int PROCESS_SUMMARY_MAX_LINES = 8;

    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final AgentTurnRepository turns;
    private final AgentMessageCacheService messageCache;
    private final AgentRuntimeService agentRuntimeService;
    private final ObjectMapper objectMapper;
    private final UserSettingsService userSettingsService;
    private final ConversationIntentRouterService conversationIntentRouterService;
    private final SkillsService skillsService;
    private final AgentToolPolicyEngine toolPolicyEngine;
    private final AgentStrategySelector agentStrategySelector;
    private final AgentExperimentService agentExperimentService;
    private final AgentMemoryExperimentService agentMemoryExperimentService;
    private final AgentExperimentRecordService agentExperimentRecordService;
    private final AgentContextBuilder agentContextBuilder;
    private final AgentContextSnapshotService contextSnapshotService;
    private final AgentSessionSummaryService sessionSummaryService;
    private final LongTermMemoryRetrievalService longTermMemoryRetrievalService;
    private final ChatModelProvider titleModelProvider;
    private final UserAccountPolicy accountPolicy;
    private final AgentRequestDedupService requestDedupService;

    public AgentService(AgentSessionRepository sessions,
                        AgentMessageRepository messages,
                        AgentTurnRepository turns,
                        AgentMessageCacheService messageCache,
                        AgentRuntimeService agentRuntimeService,
                        ObjectMapper objectMapper,
                        UserSettingsService userSettingsService,
                        ConversationIntentRouterService conversationIntentRouterService,
                        SkillsService skillsService,
                        AgentToolPolicyEngine toolPolicyEngine,
                        AgentStrategySelector agentStrategySelector,
                        AgentExperimentService agentExperimentService,
                        AgentMemoryExperimentService agentMemoryExperimentService,
                        AgentExperimentRecordService agentExperimentRecordService,
                        AgentContextBuilder agentContextBuilder,
                        AgentContextSnapshotService contextSnapshotService,
                        AgentSessionSummaryService sessionSummaryService,
                        LongTermMemoryRetrievalService longTermMemoryRetrievalService,
                        @Qualifier("chatModelProvider") ChatModelProvider titleModelProvider,
                        UserAccountPolicy accountPolicy,
                        AgentRequestDedupService requestDedupService) {
        this.sessions = sessions;
        this.messages = messages;
        this.turns = turns;
        this.messageCache = messageCache;
        this.agentRuntimeService = agentRuntimeService;
        this.objectMapper = objectMapper;
        this.userSettingsService = userSettingsService;
        this.conversationIntentRouterService = conversationIntentRouterService;
        this.skillsService = skillsService;
        this.toolPolicyEngine = toolPolicyEngine;
        this.agentStrategySelector = agentStrategySelector;
        this.agentExperimentService = agentExperimentService;
        this.agentMemoryExperimentService = agentMemoryExperimentService;
        this.agentExperimentRecordService = agentExperimentRecordService;
        this.agentContextBuilder = agentContextBuilder;
        this.contextSnapshotService = contextSnapshotService;
        this.sessionSummaryService = sessionSummaryService;
        this.longTermMemoryRetrievalService = longTermMemoryRetrievalService;
        this.titleModelProvider = titleModelProvider;
        this.accountPolicy = accountPolicy;
        this.requestDedupService = requestDedupService;
    }

    @Transactional
    public AgentSessionResponse createSession(Long userId, CreateSessionRequest request) {
        SysUserSettings settings = userSettingsService.getOrCreate(userId);
        String requestedProvider = StringUtils.hasText(request.modelProvider()) ? request.modelProvider().trim() : settings.getDefaultProvider();
        UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                userId,
                requestedProvider,
                StringUtils.hasText(request.model()) ? request.model().trim() : null);
        AgentSession session = new AgentSession(
                userId,
                StringUtils.hasText(request.title()) ? request.title().trim() : "新会话",
                endpoint.providerKey(),
                endpoint.modelName(),
                request.maxSteps() == null ? settings.getMaxSteps() : request.maxSteps(),
                request.ragDisabled() != null ? request.ragDisabled() : !Boolean.TRUE.equals(settings.getRagDefaultEnabled())
        );
        AgentSession saved = sessions.saveAndFlush(session);
        messageCache.evictSession(userId, saved.getId());
        return AgentSessionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AgentSessionResponse> listSessions(Long userId) {
        return sessions.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(AgentSessionResponse::from)
                .toList();
    }

    @Transactional
    public AgentSessionResponse updateSession(Long userId, Long sessionId, UpdateSessionRequest request) {
        AgentSession session = getOwnedSession(userId, sessionId);
        if (request.title() != null) {
            String title = request.title().trim();
            if (!StringUtils.hasText(title)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话标题不能为空");
            }
            session.updateTitle(title);
        }
        if (StringUtils.hasText(request.modelProvider()) || StringUtils.hasText(request.model())) {
            String provider = StringUtils.hasText(request.modelProvider())
                    ? request.modelProvider().trim()
                    : session.getModelProviderSnapshot();
            UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                    userId,
                    provider,
                    StringUtils.hasText(request.model()) ? request.model().trim() : null);
            session.updateModel(endpoint.providerKey(), endpoint.modelName());
        }
        if (request.maxSteps() != null) {
            session.updateMaxSteps(request.maxSteps());
        }
        if (request.ragDisabled() != null) {
            session.updateRagDisabled(request.ragDisabled());
        }
        session.touch();
        return AgentSessionResponse.from(sessions.saveAndFlush(session));
    }

    @Transactional
    public void deleteSession(Long userId, Long sessionId) {
        AgentSession session = getOwnedSession(userId, sessionId);
        turns.deleteBySessionId(session.getId());
        messages.deleteBySessionId(session.getId());
        messageCache.evictSession(userId, session.getId());
        sessions.delete(session);
        sessions.flush();
    }

    @Transactional(readOnly = true)
    public List<AgentMessageResponse> listMessages(Long userId, Long sessionId, Integer limit, Long beforeId, String view) {
        AgentSession session = getOwnedSession(userId, sessionId);
        int safeLimit = safeMessageLimit(limit);
        boolean chatView = view == null || view.isBlank() || "chat".equalsIgnoreCase(view);
        if (chatView && beforeId == null) {
            Optional<List<AgentMessageResponse>> cached = messageCache.getRecentVisibleMessages(userId, session.getId(), safeLimit);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        Pageable page = PageRequest.of(0, safeLimit);
        List<AgentMessage> rows;
        if (chatView) {
            rows = beforeId == null
                    ? messages.findBySessionIdAndRoleInOrderByIdDesc(session.getId(), CHAT_VISIBLE_ROLES, page)
                    : messages.findBySessionIdAndRoleInAndIdLessThanOrderByIdDesc(session.getId(), CHAT_VISIBLE_ROLES, beforeId, page);
        } else {
            rows = beforeId == null
                    ? messages.findBySessionIdOrderByIdDesc(session.getId(), page)
                    : messages.findBySessionIdAndIdLessThanOrderByIdDesc(session.getId(), beforeId, page);
        }
        Collections.reverse(rows);
        List<AgentMessageResponse> response = rows.stream()
                .filter(message -> !chatView || isChatVisibleMessage(message))
                .map(AgentMessageResponse::from)
                .toList();
        if (chatView && beforeId == null) {
            messageCache.putRecentVisibleMessages(userId, session.getId(), response);
        }
        return response;
    }

    public SendMessageResponse sendMessage(Long userId, Long sessionId, SendMessageRequest request) {
        return requestDedupService.execute(
                userId,
                sessionId,
                request.clientRequestId(),
                () -> sendMessageInternal(userId, sessionId, request, null, null)
        );
    }

    public SendMessageResponse sendMessageStreaming(Long userId,
                                                    Long sessionId,
                                                    SendMessageRequest request,
                                                    Consumer<String> tokenConsumer) {
        return sendMessageStreaming(userId, sessionId, request, tokenConsumer, null);
    }

    public SendMessageResponse sendMessageStreaming(Long userId,
                                                    Long sessionId,
                                                    SendMessageRequest request,
                                                    Consumer<String> tokenConsumer,
                                                    Consumer<String> processConsumer) {
        return requestDedupService.execute(
                userId,
                sessionId,
                request.clientRequestId(),
                () -> sendMessageInternal(userId, sessionId, request, tokenConsumer, processConsumer)
        );
    }

    private SendMessageResponse sendMessageInternal(Long userId,
                                                    Long sessionId,
                                                    SendMessageRequest request,
                                                    Consumer<String> tokenConsumer,
                                                    Consumer<String> processConsumer) {
        long startedAtNanos = System.nanoTime();
        accountPolicy.assertCanSendChatMessage(userId);
        AgentSession session = getOwnedSession(userId, sessionId);
        UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                userId, session.getModelProviderSnapshot(), session.getModelSnapshot());
        ModelSourceDebug modelSource = toModelSource(endpoint);
        AgentExperimentContext experimentContext = agentExperimentService.prepare(
                userId,
                request.content(),
                request.experiment()
        );
        AgentContextBuildRequest contextBuildRequest = new AgentContextBuildRequest(
                session.getId(),
                userId,
                endpoint.providerKey(),
                endpoint.modelName(),
                loadSessionSummaryText(session.getId(), userId),
                loadLongTermMemoryContext(userId, request.content()),
                experimentContext.ragResult() == null ? null : experimentContext.ragResult().ragContext(),
                null,
                null,
                null
        );
        AgentMemoryExperimentResult memoryExperiment = agentMemoryExperimentService.buildContext(experimentContext, contextBuildRequest);
        AgentContextPackage contextPackage = memoryExperiment.contextPackage();
        boolean shouldAutoGenerateTitle = shouldAutoGenerateTitle(session, contextPackage.rawMessageCount());

        List<AgentMessage> saved = new ArrayList<>();
        AgentMessage userMessage = saveAndCacheMessage(session.getId(), userId, ChatMessage.user(request.content()));
        saved.add(userMessage);
        AgentTurn turn = createRunningTurn(session.getId(), userId, userMessage.getId());
        saveContextSnapshot(turn, contextPackage);

        if (isRuntimeIdentityQuestion(request.content())) {
            String assistantContent = buildRuntimeIdentityAnswer(endpoint);
            AgentMessage processMessage = saveProcessMessageIfNeeded(
                    session.getId(),
                    userId,
                    "已分析问题，直接生成回答。"
            );
            if (processMessage != null) {
                saved.add(processMessage);
            }
            AgentMessage assistantMessage = saveAndCacheMessage(session.getId(), userId, ChatMessage.assistant(assistantContent));
            saved.add(assistantMessage);
            completeTurn(turn, assistantMessage.getId());
            emitToken(tokenConsumer, assistantContent);
            updateSessionSummaryAfterSuccess(
                    session,
                    userId,
                    contextPackage,
                    request.content(),
                    assistantContent,
                    assistantMessage.getId(),
                    saved.size()
            );
            touchAndMaybeGenerateTitle(session, userId, request.content(), shouldAutoGenerateTitle);
            AgentDebugPayload debugPayload = finalizeDebugPayload(
                    userId,
                    session.getId(),
                    request.clientRequestId(),
                    experimentContext,
                    memoryExperiment,
                    null,
                    contextPackage.messages(),
                    assistantContent,
                    true,
                    null,
                    elapsedMillis(startedAtNanos),
                    modelSource
            );
            return new SendMessageResponse(
                    true,
                    assistantContent,
                    0,
                    null,
                    null,
                    saved.stream().map(AgentMessageResponse::from).toList(),
                    debugPayload
            );
        }

        ConversationIntentRouterService.IntentAction intentAction = conversationIntentRouterService.route(request.content());
        if (intentAction != null) {
            AgentMessage processMessage = saveProcessMessageIfNeeded(
                    session.getId(),
                    userId,
                    buildIntentProcessSummary(intentAction)
            );
            if (processMessage != null) {
                saved.add(processMessage);
            }
            AgentMessage assistantMessage = saveAndCacheMessage(session.getId(), userId, ChatMessage.assistant(intentAction.assistantMessage()));
            saved.add(assistantMessage);
            completeTurn(turn, assistantMessage.getId());
            emitToken(tokenConsumer, intentAction.assistantMessage());
            updateSessionSummaryAfterSuccess(
                    session,
                    userId,
                    contextPackage,
                    request.content(),
                    intentAction.assistantMessage(),
                    assistantMessage.getId(),
                    saved.size()
            );
            touchAndMaybeGenerateTitle(session, userId, request.content(), shouldAutoGenerateTitle);
            AgentDebugPayload debugPayload = finalizeDebugPayload(
                    userId,
                    session.getId(),
                    request.clientRequestId(),
                    experimentContext,
                    memoryExperiment,
                    null,
                    contextPackage.messages(),
                    intentAction.assistantMessage(),
                    true,
                    null,
                    elapsedMillis(startedAtNanos),
                    modelSource
            );
            return new SendMessageResponse(
                    true,
                    intentAction.assistantMessage(),
                    0,
                    null,
                    intentAction.navigationUrl(),
                    saved.stream().map(AgentMessageResponse::from).toList(),
                    debugPayload
            );
        }

        boolean ragDisabled = request.ragDisabled() != null ? request.ragDisabled() : Boolean.TRUE.equals(session.getRagDisabled());
        boolean runtimeRagDisabled = ragDisabled || experimentContext.overridesRag();
        ResolvedSkill resolvedSkill = request.skillId() == null || request.skillId().isBlank()
                ? null
                : skillsService.resolveEnabledSkill(userId, request.skillId());
        AgentToolPolicyEngine.Decision toolPolicy = toolPolicyEngine.decide(
                request.content(),
                runtimeRagDisabled,
                resolvedSkill == null ? null : resolvedSkill.allowedTools()
        );
        log.info("Agent tool policy sessionId={} provider={} model={} sourceType={} sourceLabel={} allowedTools={} reason={}",
                session.getId(),
                endpoint.providerKey(),
                endpoint.modelName(),
                endpoint.sourceType(),
                endpoint.sourceLabel(),
                toolPolicy.allowedTools(),
                toolPolicy.reason());
        List<ChatMessage> effectiveHistory = contextPackage.messages();
        log.debug("Agent context built sessionId={} rawMessages={} normalizedMessages={} contextMessages={} estimatedCharacters={} droppedItems={}",
                session.getId(),
                contextPackage.rawMessageCount(),
                contextPackage.normalizedMessageCount(),
                effectiveHistory.size(),
                contextPackage.estimatedCharacters(),
                contextPackage.droppedItems());
        log.debug("Agent context sections sessionId={} sections={}", session.getId(), contextPackage.sections());
        try {
            AgentRuntimeResult result = agentRuntimeService.run(new AgentRuntimeRequest(
                    agentStrategySelector.select(request.content(), toolPolicy),
                    session.getId(),
                    effectiveHistory,
                    userId,
                    request.content(),
                    endpoint.providerKey(),
                    endpoint.modelName(),
                    null,
                    null,
                    session.getMaxSteps(),
                    runtimeRagDisabled,
                    request.skillId(),
                    endpoint.apiKey(),
                    endpoint.apiUrl(),
                    resolvedSkill == null ? null : resolvedSkill.prompt(),
                    experimentContext.selectedModes().runtimeMode(),
                    experimentContext.selectedModes().toolCallingMode(),
                    toolPolicy.allowedTools(),
                    toolPolicy.maxToolCalls(),
                    toolPolicy.maxDuplicateToolCalls(),
                    MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
                    tokenConsumer,
                    processConsumer
            ));

            AgentMessage processMessage = saveProcessSummaryIfNeeded(session.getId(), userId, result, experimentContext);
            if (processMessage != null) {
                saved.add(processMessage);
            }
            List<AgentMessage> runtimeMessages = saveRuntimeMessages(
                    session.getId(),
                    userId,
                    result.messages(),
                    effectiveHistory.size()
            );
            saved.addAll(runtimeMessages);
            log.info("Agent runtime completed sessionId={} userId={} success={} steps={} assistantPreview={} toolTrace={} fallbacks={} promptTokens={} completionTokens={} totalTokens={}",
                    session.getId(),
                    userId,
                    result.success(),
                    result.steps(),
                    abbreviateForLog(result.assistantContent()),
                    result.toolTrace(),
                    result.fallbacks(),
                    result.promptTokens(),
                    result.completionTokens(),
                    result.totalTokens());

            if (result.success()) {
                Long assistantMessageId = lastAssistantMessageId(runtimeMessages);
                completeTurn(turn, assistantMessageId);
                updateSessionSummaryAfterSuccess(
                        session,
                        userId,
                        contextPackage,
                        request.content(),
                        result.assistantContent(),
                        assistantMessageId,
                        1 + runtimeMessages.size()
                );
                touchAndMaybeGenerateTitle(session, userId, request.content(), shouldAutoGenerateTitle);
                AgentDebugPayload debugPayload = finalizeDebugPayload(
                        userId,
                        session.getId(),
                        request.clientRequestId(),
                        experimentContext,
                    memoryExperiment,
                    result,
                    effectiveHistory,
                    result.assistantContent(),
                    true,
                    null,
                    elapsedMillis(startedAtNanos),
                    modelSource
                );
                return new SendMessageResponse(
                        true,
                        result.assistantContent(),
                        result.steps(),
                        null,
                        null,
                        saved.stream().map(AgentMessageResponse::from).toList(),
                        debugPayload
                );
            }

            return failTurn(
                    session,
                    userId,
                    turn,
                    saved,
                    result.errorMessage(),
                    result.steps(),
                    experimentContext,
                    memoryExperiment,
                    result,
                    effectiveHistory,
                    request.clientRequestId(),
                    elapsedMillis(startedAtNanos),
                    modelSource
            );
        } catch (Exception ex) {
            log.warn("Agent send failed sessionId={} userId={}", session.getId(), userId, ex);
            return failTurn(
                    session,
                    userId,
                    turn,
                    saved,
                    extractErrorMessage(ex),
                    0,
                    experimentContext,
                    memoryExperiment,
                    null,
                    effectiveHistory,
                    request.clientRequestId(),
                    elapsedMillis(startedAtNanos),
                    modelSource
            );
        }
    }

    private String loadSessionSummaryText(Long sessionId, Long userId) {
        try {
            return sessionSummaryService.findBySessionAndUser(sessionId, userId)
                    .map(AgentSessionSummary::getSummaryText)
                    .filter(StringUtils::hasText)
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Failed to load agent session summary sessionId={} userId={}", sessionId, userId, ex);
            return null;
        }
    }

    private AgentLongTermMemoryContext loadLongTermMemoryContext(Long userId, String content) {
        return AgentLongTermMemoryContext.empty();
    }

    private void saveContextSnapshot(AgentTurn turn, AgentContextPackage contextPackage) {
        if (turn == null) {
            return;
        }
        try {
            contextSnapshotService.saveSnapshot(
                    turn.getId(),
                    turn.getSessionId(),
                    turn.getUserId(),
                    MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
                    contextPackage
            );
        } catch (Exception ex) {
            log.warn("Failed to save agent context snapshot turnId={} sessionId={} userId={}",
                    turn.getId(), turn.getSessionId(), turn.getUserId(), ex);
        }
    }

    private void updateSessionSummaryAfterSuccess(AgentSession session,
                                                  Long userId,
                                                  AgentContextPackage contextPackage,
                                                  String userContent,
                                                  String assistantContent,
                                                  Long assistantMessageId,
                                                  int newPersistedMessageCount) {
        try {
            String existingSummary = loadSessionSummaryText(session.getId(), userId);
            String nextSummary = buildNextSessionSummary(existingSummary, userContent, assistantContent);
            sessionSummaryService.upsert(new AgentSessionSummaryUpdate(
                    session.getId(),
                    userId,
                    nextSummary,
                    assistantMessageId,
                    contextPackage.rawMessageCount() + Math.max(0, newPersistedMessageCount),
                    session.getModelProviderSnapshot(),
                    session.getModelSnapshot()
            ));
        } catch (Exception ex) {
            log.warn("Failed to update agent session summary sessionId={} userId={}", session.getId(), userId, ex);
        }
    }

    private String buildNextSessionSummary(String existingSummary, String userContent, String assistantContent) {
        List<String> facts = extractDurableFacts(existingSummary);
        String latestFact = latestDurableFact(userContent);
        if (StringUtils.hasText(latestFact) && facts.stream().noneMatch(fact -> fact.equalsIgnoreCase(latestFact))) {
            facts.add(latestFact);
        }
        if (facts.size() > SESSION_FACT_MAX_COUNT) {
            facts = new ArrayList<>(facts.subList(facts.size() - SESSION_FACT_MAX_COUNT, facts.size()));
        }

        StringBuilder summary = new StringBuilder("""
                User goals:
                - Continue the current conversation using the latest user request and saved session facts.

                Confirmed facts and constraints:
                """);
        if (facts.isEmpty()) {
            summary.append("- No durable facts recorded yet.\n");
        } else {
            for (String fact : facts) {
                summary.append("- ").append(fact).append("\n");
            }
        }
        summary.append("\nCurrent task progress:\n")
                .append("- Latest user message: ")
                .append(summarizeForSessionMemory(userContent))
                .append("\n")
                .append("- Latest assistant response: ")
                .append(summarizeForSessionMemory(assistantContent))
                .append("\n\nOpen questions:\n")
                .append("- None recorded unless the latest turn explicitly raised one.");
        return trimToMax(summary.toString(), SESSION_SUMMARY_MAX_CHARACTERS);
    }

    private List<String> extractDurableFacts(String existingSummary) {
        if (!StringUtils.hasText(existingSummary)) {
            return new ArrayList<>();
        }
        List<String> facts = new ArrayList<>();
        boolean inFacts = false;
        for (String rawLine : existingSummary.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.equalsIgnoreCase("Confirmed facts and constraints:")) {
                inFacts = true;
                continue;
            }
            if (inFacts && (line.equalsIgnoreCase("Current task progress:")
                    || line.equalsIgnoreCase("Open questions:")
                    || line.equalsIgnoreCase("User goals:"))) {
                inFacts = false;
            }
            if (!line.startsWith("- ")) {
                continue;
            }
            String fact = normalizeFactLine(line.substring(2));
            if (!StringUtils.hasText(fact) || fact.equalsIgnoreCase("No durable facts recorded yet.")
                    || fact.equalsIgnoreCase("No previous session summary.")) {
                continue;
            }
            if (inFacts || isLikelyDurableFact(fact)) {
                addUniqueFact(facts, fact);
            }
        }
        return facts;
    }

    private String latestDurableFact(String userContent) {
        if (!isLikelyDurableFact(userContent)) {
            return null;
        }
        return normalizeFactLine(userContent);
    }

    private boolean isLikelyDurableFact(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String normalized = content.trim().toLowerCase();
        return normalized.contains("记住")
                || normalized.contains("正式名称")
                || normalized.contains("英文代号")
                || normalized.contains("项目名称")
                || normalized.contains("我的项目")
                || normalized.contains("我叫")
                || normalized.contains("叫“")
                || normalized.contains("叫\"")
                || normalized.contains("偏好")
                || normalized.contains("习惯")
                || normalized.contains("remember")
                || normalized.contains("my name")
                || normalized.contains("project name")
                || normalized.contains("official name")
                || normalized.contains("codename")
                || normalized.contains("prefer");
    }

    private void addUniqueFact(List<String> facts, String fact) {
        if (!StringUtils.hasText(fact)) {
            return;
        }
        for (String existing : facts) {
            if (existing.equalsIgnoreCase(fact)) {
                return;
            }
        }
        facts.add(fact);
    }

    private String normalizeFactLine(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.trim()
                .replaceFirst("(?i)^latest user message:\\s*", "")
                .replaceFirst("(?i)^user:\\s*", "")
                .replaceAll("\\s+", " ");
        return trimToMax(normalized, SESSION_FACT_MAX_CHARACTERS);
    }

    private String summarizeForSessionMemory(String content) {
        if (!StringUtils.hasText(content)) {
            return "(empty)";
        }
        return trimToMax(content.trim().replaceAll("\\s+", " "), SUMMARY_TURN_SNIPPET_MAX_CHARACTERS);
    }

    private String trimToMax(String content, int maxCharacters) {
        if (content == null || content.length() <= maxCharacters) {
            return content;
        }
        int markerLength = "\n...[truncated]...\n".length();
        if (maxCharacters <= markerLength + 2) {
            return content.substring(0, maxCharacters).trim();
        }
        int headLength = Math.max(1, (maxCharacters - markerLength) / 2);
        int tailLength = Math.max(1, maxCharacters - markerLength - headLength);
        return (content.substring(0, headLength).trim()
                + "\n...[truncated]...\n"
                + content.substring(content.length() - tailLength).trim()).trim();
    }

    private void emitToken(Consumer<String> tokenConsumer, String content) {
        if (tokenConsumer != null && StringUtils.hasText(content)) {
            tokenConsumer.accept(content);
        }
    }

    private int safeMessageLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_VISIBLE_MESSAGE_LIMIT;
        }
        return Math.max(1, Math.min(MAX_VISIBLE_MESSAGE_LIMIT, limit));
    }

    private AgentTurn createRunningTurn(Long sessionId, Long userId, Long userMessageId) {
        AgentTurn turn = turns.saveAndFlush(new AgentTurn(sessionId, userId, userMessageId));
        messageCache.putTurnStatus(turn.getId(), turn.getStatus(), null);
        return turn;
    }

    private void completeTurn(AgentTurn turn, Long assistantMessageId) {
        if (turn == null) {
            return;
        }
        turn.complete(assistantMessageId);
        turns.saveAndFlush(turn);
        messageCache.putTurnStatus(turn.getId(), turn.getStatus(), null);
    }

    private SendMessageResponse failTurn(AgentSession session,
                                         Long userId,
                                         AgentTurn turn,
                                         List<AgentMessage> saved,
                                         String errorMessage,
                                         int steps,
                                         AgentExperimentContext experimentContext,
                                         AgentMemoryExperimentResult memoryExperiment,
                                         AgentRuntimeResult runtimeResult,
                                         List<ChatMessage> effectiveHistory,
                                         String clientRequestId,
                                         long latencyMs,
                                         ModelSourceDebug modelSource) {
        String safeError = StringUtils.hasText(errorMessage) ? errorMessage.trim() : "对话处理失败";
        AgentMessage assistantMessage = saveAndCacheMessage(
                session.getId(),
                userId,
                ChatMessage.assistant("这次回复失败了：" + safeError)
        );
        saved.add(assistantMessage);
        if (runtimeResult != null) {
            log.warn("Agent runtime failed sessionId={} userId={} steps={} error={} toolTrace={} fallbacks={} assistantPreview={}",
                    session.getId(),
                    userId,
                    runtimeResult.steps(),
                    safeError,
                    runtimeResult.toolTrace(),
                    runtimeResult.fallbacks(),
                    abbreviateForLog(runtimeResult.assistantContent()));
        }
        if (turn != null) {
            turn.fail(assistantMessage.getId(), safeError);
            turns.saveAndFlush(turn);
            messageCache.putTurnStatus(turn.getId(), turn.getStatus(), safeError);
        }
        session.touch();
        sessions.saveAndFlush(session);
        AgentDebugPayload debugPayload = finalizeDebugPayload(
                userId,
                session.getId(),
                clientRequestId,
                experimentContext,
                memoryExperiment,
                runtimeResult,
                effectiveHistory,
                assistantMessage.getContent(),
                false,
                safeError,
                latencyMs,
                modelSource
        );
        return new SendMessageResponse(
                false,
                assistantMessage.getContent(),
                steps,
                safeError,
                null,
                saved.stream().map(AgentMessageResponse::from).toList(),
                debugPayload
        );
    }

    private AgentDebugPayload finalizeDebugPayload(Long userId,
                                                   Long sessionId,
                                                   String clientRequestId,
                                                   AgentExperimentContext experimentContext,
                                                   AgentMemoryExperimentResult memoryExperiment,
                                                   AgentRuntimeResult runtimeResult,
                                                   List<ChatMessage> effectiveHistory,
                                                   String assistantContent,
                                                   boolean success,
                                                   String errorMessage,
                                                   long latencyMs,
                                                   ModelSourceDebug modelSource) {
        AgentDebugPayload basePayload = agentExperimentService.toDebugPayload(experimentContext);
        boolean showMemoryWindow = experimentContext != null && experimentContext.hasFlag(AgentDebugFlag.SHOW_MEMORY_WINDOW);
        boolean showRawPrompt = experimentContext != null && experimentContext.hasFlag(AgentDebugFlag.SHOW_RAW_PROMPT);
        boolean showToolTrace = experimentContext != null && experimentContext.hasFlag(AgentDebugFlag.SHOW_TOOL_TRACE);
        AgentMemoryWindowDebug memoryWindow = showMemoryWindow
                ? memoryExperiment.memoryWindow()
                : null;
        List<String> fallbacks = new ArrayList<>();
        if (runtimeResult != null && runtimeResult.fallbacks() != null) {
            fallbacks.addAll(runtimeResult.fallbacks());
        }
        if (errorMessage != null) {
            fallbacks.add(errorMessage);
        }
        AgentExperimentMetricsDebug metrics = new AgentExperimentMetricsDebug(
                clientRequestId,
                sessionId,
                latencyMs,
                basePayload == null ? 0 : basePayload.retrievedChunks().size(),
                memoryWindow == null ? 0 : memoryWindow.entries().size(),
                runtimeResult == null ? null : runtimeResult.promptTokens(),
                runtimeResult == null ? null : runtimeResult.completionTokens(),
                runtimeResult == null ? null : runtimeResult.totalTokens(),
                runtimeResult == null || runtimeResult.toolTrace() == null ? 0 : runtimeResult.toolTrace().size(),
                runtimeResult == null ? null : runtimeResult.steps(),
                null
        );
        if (basePayload == null) {
            return new AgentDebugPayload(
                    experimentContext == null ? null : experimentContext.selectedModes(),
                    List.of(),
                    null,
                    null,
                    List.of(),
                    runtimeResult == null ? List.of() : runtimeResult.toolTrace(),
                    List.of(),
                    metrics,
                    null,
                    fallbacks,
                    modelSource
            );
        }
        List<String> finalCitations = extractFinalCitations(assistantContent, basePayload.retrievedChunks());
        String rawPrompt = showRawPrompt ? buildRawPromptPreview(effectiveHistory) : null;
        AgentDebugPayload debugPayload = new AgentDebugPayload(
                basePayload.selectedModes(),
                basePayload.retrievedChunks(),
                basePayload.injectedContext(),
                rawPrompt,
                basePayload.debugFlags(),
                runtimeResult == null || !showToolTrace
                        ? basePayload.toolTrace()
                        : runtimeResult.toolTrace(),
                finalCitations,
                metrics,
                memoryWindow,
                fallbacks,
                modelSource
        );
        Long evalRecordId = agentExperimentRecordService.persistIfEnabled(
                userId,
                sessionId,
                clientRequestId,
                experimentContext,
                debugPayload,
                assistantContent,
                success,
                errorMessage,
                latencyMs
        );
        AgentExperimentMetricsDebug updatedMetrics = new AgentExperimentMetricsDebug(
                metrics.clientRequestId(),
                metrics.sessionId(),
                metrics.latencyMs(),
                metrics.retrievedChunkCount(),
                metrics.memoryWindowSize(),
                metrics.promptTokens(),
                metrics.completionTokens(),
                metrics.totalTokens(),
                metrics.toolCallCount(),
                metrics.steps(),
                evalRecordId
        );
        return new AgentDebugPayload(
                debugPayload.selectedModes(),
                debugPayload.retrievedChunks(),
                debugPayload.injectedContext(),
                debugPayload.rawPrompt(),
                debugPayload.debugFlags(),
                debugPayload.toolTrace(),
                debugPayload.finalCitations(),
                updatedMetrics,
                debugPayload.memoryWindow(),
                debugPayload.fallbacks(),
                debugPayload.modelSource()
        );
    }

    private String buildRawPromptPreview(List<ChatMessage> effectiveHistory) {
        if (effectiveHistory == null || effectiveHistory.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : effectiveHistory) {
            if (message == null) {
                continue;
            }
            sb.append("[").append(message.role()).append("]\n");
            if (message.content() != null) {
                sb.append(message.content());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                sb.append("\n# toolCalls=").append(message.toolCalls().size());
            }
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    private List<String> extractFinalCitations(String assistantContent, List<AgentRetrievedChunkDebug> chunks) {
        if (!StringUtils.hasText(assistantContent) || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> citations = new ArrayList<>();
        for (AgentRetrievedChunkDebug chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.citationId())) {
                continue;
            }
            if (assistantContent.contains(chunk.citationId())) {
                citations.add(chunk.citationId());
            }
        }
        return citations;
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private String abbreviateForLog(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
    }

    private AgentMessage saveProcessSummaryIfNeeded(Long sessionId,
                                                    Long userId,
                                                    AgentRuntimeResult result,
                                                    AgentExperimentContext experimentContext) {
        String summary = buildProcessSummary(result, experimentContext);
        return saveProcessMessageIfNeeded(sessionId, userId, summary);
    }

    private AgentMessage saveProcessMessageIfNeeded(Long sessionId, Long userId, String summary) {
        if (!StringUtils.hasText(summary)) {
            return null;
        }
        return saveAndCacheMessage(sessionId, userId, ChatMessage.process(summary));
    }

    private String buildProcessSummary(AgentRuntimeResult result, AgentExperimentContext experimentContext) {
        if (result == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        appendRagProcessLines(lines, experimentContext);
        if (result.toolTrace() != null && !result.toolTrace().isEmpty()) {
            for (String trace : result.toolTrace()) {
                String toolName = extractToolName(trace);
                lines.add(processStartLabel(toolName));
                lines.add(processDoneLabel(toolName, trace != null && trace.contains("success=true")));
                if (lines.size() >= PROCESS_SUMMARY_MAX_LINES) {
                    break;
                }
            }
        } else if (lines.isEmpty() && result.steps() > 0) {
            lines.add("已分析问题，直接生成回答。");
        }
        if (result.fallbacks() != null && !result.fallbacks().isEmpty() && lines.size() < PROCESS_SUMMARY_MAX_LINES) {
            lines.add("部分步骤未完成，已尝试降级处理。");
        }
        return String.join("\n", lines.stream()
                .filter(StringUtils::hasText)
                .limit(PROCESS_SUMMARY_MAX_LINES)
                .toList());
    }

    private void appendRagProcessLines(List<String> lines, AgentExperimentContext experimentContext) {
        if (lines == null || experimentContext == null || experimentContext.ragResult() == null) {
            return;
        }
        int retrieved = experimentContext.ragResult().retrievedChunks().size();
        lines.add("正在检索知识库。");
        lines.add(retrieved > 0
                ? "知识库检索完成，找到 " + retrieved + " 个相关片段。"
                : "知识库检索完成，未找到明显相关片段。");
    }

    private String buildIntentProcessSummary(ConversationIntentRouterService.IntentAction action) {
        if (action == null || !StringUtils.hasText(action.intent())) {
            return "已分析问题，直接生成回答。";
        }
        return switch (action.intent()) {
            case "PAPER_REVISION" -> "正在识别论文润色任务。\n已准备跳转到论文修改页。";
            case "LITERATURE_SEARCH" -> "正在检索论文资料。\n文献检索完成。";
            case "LITERATURE_SEARCH_CLARIFY", "LITERATURE_SEARCH_CONFIRM" -> "已分析文献检索需求，需要补充信息。";
            default -> "已分析问题，直接生成回答。";
        };
    }

    private String extractToolName(String trace) {
        if (!StringUtils.hasText(trace)) {
            return null;
        }
        String marker = "tool=";
        int start = trace.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int nameStart = start + marker.length();
        int nameEnd = trace.indexOf(' ', nameStart);
        return nameEnd < 0 ? trace.substring(nameStart).trim() : trace.substring(nameStart, nameEnd).trim();
    }

    private String processStartLabel(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "search_knowledge" -> "正在检索知识库。";
            case "search_web" -> "正在联网搜索资料。";
            case "recommend_literature" -> "正在检索并整理相关文献。";
            case "literature_search_start" -> "正在创建文献检索任务。";
            case "literature_search_status" -> "正在查看文献检索进度。";
            case "literature_search_result" -> "正在读取文献检索结果。";
            case "literature_search_cancel", "paper_task_cancel" -> "正在取消后台任务。";
            case "paper_polish_status" -> "正在查看论文润色进度。";
            case "paper_polish_result" -> "正在读取论文润色结果。";
            default -> "正在调用辅助工具。";
        };
    }

    private String processDoneLabel(String toolName, boolean success) {
        if (!success) {
            return "工具调用未完成，已尝试继续处理。";
        }
        return switch (toolName == null ? "" : toolName) {
            case "search_knowledge" -> "知识库检索完成。";
            case "search_web" -> "联网搜索完成。";
            case "recommend_literature", "literature_search_result" -> "文献检索完成。";
            case "literature_search_start" -> "文献检索任务已创建。";
            case "literature_search_status", "paper_polish_status" -> "后台任务状态已更新。";
            case "literature_search_cancel", "paper_task_cancel" -> "后台任务已取消。";
            case "paper_polish_result" -> "论文润色结果已读取。";
            default -> "工具调用完成。";
        };
    }

    private List<AgentMessage> saveRuntimeMessages(Long sessionId,
                                                   Long userId,
                                                   List<ChatMessage> allMessages,
                                                   int persistedHistorySize) {
        if (allMessages == null || allMessages.size() <= persistedHistorySize) {
            return List.of();
        }
        List<AgentMessage> saved = new ArrayList<>();
        for (ChatMessage chatMessage : allMessages.subList(persistedHistorySize, allMessages.size())) {
            if (!shouldPersistRuntimeMessage(chatMessage)) {
                continue;
            }
            saved.add(saveAndCacheMessage(sessionId, userId, chatMessage));
        }
        return saved;
    }

    private boolean shouldPersistRuntimeMessage(ChatMessage chatMessage) {
        if (chatMessage == null || chatMessage.role() == null) {
            return false;
        }
        String role = chatMessage.role().trim().toLowerCase();
        return "assistant".equals(role) || "tool".equals(role);
    }

    private boolean isChatVisibleMessage(AgentMessage message) {
        if (message == null || message.getRole() == null) {
            return false;
        }
        String role = message.getRole().trim().toLowerCase();
        return "user".equals(role) || ("assistant".equals(role) && !StringUtils.hasText(message.getToolCallsJson()));
    }

    private Long lastAssistantMessageId(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage message = messages.get(i);
            if (message != null && "assistant".equalsIgnoreCase(message.getRole())) {
                return message.getId();
            }
        }
        return null;
    }

    private AgentMessage saveAndCacheMessage(Long sessionId, Long userId, ChatMessage chatMessage) {
        AgentMessage saved = messages.saveAndFlush(toAgentMessage(sessionId, userId, chatMessage));
        messageCache.appendVisibleMessage(userId, sessionId, AgentMessageResponse.from(saved));
        return saved;
    }

    private String extractErrorMessage(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return "对话处理失败";
    }

    @Transactional(readOnly = true)
    public AgentSession getOwnedSession(Long userId, Long sessionId) {
        return sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> getHistoryMessages(Long sessionId) {
        return messages.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toChatMessage)
                .toList();
    }

    @Transactional
    public AgentMessage saveMessage(Long sessionId, Long userId, ChatMessage chatMessage) {
        return saveAndCacheMessage(sessionId, userId, chatMessage);
    }

    private ChatMessage toChatMessage(AgentMessage message) {
        List<ToolCall> toolCalls = parseToolCalls(message.getToolCallsJson());
        return new ChatMessage(message.getRole(), message.getContent(), toolCalls, message.getToolCallId());
    }

    private AgentMessage toAgentMessage(Long sessionId, Long userId, ChatMessage chatMessage) {
        return new AgentMessage(
                sessionId,
                userId,
                chatMessage.role(),
                chatMessage.content(),
                serializeToolCalls(chatMessage.toolCalls()),
                chatMessage.toolCallId(),
                null
        );
    }

    private void touchAndMaybeGenerateTitle(AgentSession session,
                                            Long userId,
                                            String firstUserMessage,
                                            boolean shouldAutoGenerateTitle) {
        if (shouldAutoGenerateTitle) {
            String generatedTitle = generateSessionTitle(session, userId, firstUserMessage);
            if (StringUtils.hasText(generatedTitle)) {
                session.updateTitle(generatedTitle);
            }
        }
        session.touch();
        sessions.saveAndFlush(session);
    }

    private boolean shouldAutoGenerateTitle(AgentSession session, int rawMessageCount) {
        return rawMessageCount == 0 && isDefaultSessionTitle(session.getTitle());
    }

    private boolean isRuntimeIdentityQuestion(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String normalized = content.trim().toLowerCase();
        return normalized.contains("你是什么模型")
                || normalized.contains("你是哪个模型")
                || normalized.contains("你用的什么模型")
                || normalized.contains("你是谁")
                || normalized.contains("你的模型")
                || normalized.contains("what model are you")
                || normalized.contains("which model are you")
                || normalized.contains("who are you");
    }

    private String buildRuntimeIdentityAnswer(UserSettingsService.ModelEndpoint endpoint) {
        if (endpoint != null) {
            return "我是研伴 ScholarAI 的私有研究助手。当前这个会话使用 "
                    + formatProviderForUser(endpoint.providerKey())
                    + " 的 "
                    + endpoint.modelName()
                    + " 作为模型能力。";
        }
        return "我是研伴 ScholarAI 的私有研究助手。当前这个会话使用已配置的模型作为模型能力。";
    }

    private ModelSourceDebug toModelSource(UserSettingsService.ModelEndpoint endpoint) {
        if (endpoint == null) {
            return null;
        }
        return new ModelSourceDebug(
                endpoint.providerKey(),
                endpoint.modelName(),
                endpoint.sourceType(),
                endpoint.sourceLabel());
    }

    private String formatProviderForUser(String providerKey) {
        if (!StringUtils.hasText(providerKey)) {
            return "已配置模型";
        }
        return switch (providerKey.trim().toLowerCase()) {
            case "deepseek" -> "DeepSeek";
            case "glm" -> "GLM";
            default -> providerKey.trim();
        };
    }

    private boolean isDefaultSessionTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return true;
        }
        String normalized = title.trim();
        return "新会话".equals(normalized) || "研伴对话".equals(normalized);
    }

    private String generateSessionTitle(AgentSession session, Long userId, String firstUserMessage) {
        try {
            UserSettingsService.ModelEndpoint endpoint = userSettingsService.resolveModelEndpoint(
                    userId, session.getModelProviderSnapshot(), session.getModelSnapshot());
            ChatResponse response = titleModelProvider.chat(new ChatRequest(
                    endpoint.providerKey(),
                    endpoint.modelName(),
                    List.of(
                            ChatMessage.system("你是一个会话标题生成器。只输出标题，不要解释，不要标点，不要引号。中文不超过16个字，英文不超过8个词。"),
                            ChatMessage.user("请根据用户第一条消息生成简洁会话标题：\n" + firstUserMessage)
                    ),
                    0.2,
                    64,
                    null,
                    endpoint.apiKey(),
                    endpoint.apiUrl(),
                    null,
                    null,
                    null
            ));
            return sanitizeGeneratedTitle(response == null || response.message() == null ? null : response.message().content(), firstUserMessage);
        } catch (Exception ex) {
            log.warn("Failed to generate title for session id={}", session.getId(), ex);
            return fallbackTitle(firstUserMessage);
        }
    }

    private String sanitizeGeneratedTitle(String generated, String fallbackSource) {
        String title = StringUtils.hasText(generated) ? generated.trim() : fallbackTitle(fallbackSource);
        int lineBreak = title.indexOf('\n');
        if (lineBreak >= 0) {
            title = title.substring(0, lineBreak).trim();
        }
        title = title.replaceAll("^[\\s\\\"'“”‘’《》]+|[\\s\\\"'“”‘’《》]+$", "")
                .replaceAll("[。！？!?，,；;：:]+$", "")
                .trim();
        if (!StringUtils.hasText(title)) {
            title = fallbackTitle(fallbackSource);
        }
        return title.length() <= GENERATED_TITLE_MAX_LENGTH
                ? title
                : title.substring(0, GENERATED_TITLE_MAX_LENGTH).trim();
    }

    private String fallbackTitle(String firstUserMessage) {
        if (!StringUtils.hasText(firstUserMessage)) {
            return "新会话";
        }
        String normalized = firstUserMessage.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 16 ? normalized : normalized.substring(0, 16).trim();
    }

    private String resolveDefaultModel(SysUserSettings settings, String provider) {
        return switch (provider == null ? "deepseek" : provider.trim().toLowerCase()) {
            case "glm" -> settings.getGlmModel();
            case "deepseek" -> settings.getDeepseekModel();
            default -> settings.getDeepseekModel();
        };
    }

    private List<ToolCall> parseToolCalls(String toolCallsJson) {
        if (!StringUtils.hasText(toolCallsJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(toolCallsJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "历史 tool_calls 解析失败", ex);
        }
    }

    private String serializeToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "tool_calls 序列化失败", ex);
        }
    }
}
