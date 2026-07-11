package com.yanban.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.ProjectAgentRuntimeService;
import com.yanban.api.agent.SendMessageRequest;
import com.yanban.api.agent.SendMessageResponse;
import com.yanban.api.security.JwtUser;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Authenticated, route-bound streaming transport for read-only Project conversations. */
@Component
public class ProjectChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ProjectChatWebSocketHandler.class);
    private static final String DEFAULT_WS_ERROR = "Project conversation failed";

    private final ObjectMapper objectMapper;
    private final ProjectAgentRuntimeService projectRuntime;

    public ProjectChatWebSocketHandler(ObjectMapper objectMapper,
                                       ProjectAgentRuntimeService projectRuntime) {
        this.objectMapper = objectMapper;
        this.projectRuntime = projectRuntime;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        JwtUser currentUser = currentUser(session);
        Long projectId = projectId(session);
        log.debug("Project WebSocket connected wsSessionId={} userId={} projectId={} remote={}",
                session.getId(), currentUser == null ? null : currentUser.id(), projectId, session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JwtUser currentUser = currentUser(session);
        Long projectId = projectId(session);
        if (currentUser == null || projectId == null) {
            log.warn("Project WebSocket message rejected: missing trusted binding wsSessionId={} remote={}",
                    session.getId(), session.getRemoteAddress());
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unauthorized Project binding"));
            return;
        }

        WsChatRequest request;
        try {
            request = objectMapper.readValue(message.getPayload(), WsChatRequest.class);
        } catch (Exception ex) {
            sendSafe(session, WsChatEvent.error(null, "Invalid Project chat request", null));
            return;
        }
        if (request.sessionId() == null || !StringUtils.hasText(request.content())) {
            sendSafe(session, WsChatEvent.error(request.sessionId(),
                    "sessionId and content are required", request.clientRequestId()));
            return;
        }

        log.debug("Project WebSocket inbound wsSessionId={} userId={} projectId={} chatSessionId={} contentLength={} clientRequestId={}",
                session.getId(), currentUser.id(), projectId, request.sessionId(), request.content().length(),
                request.clientRequestId());
        try {
            sendSafe(session, WsChatEvent.ack(request.sessionId(), request.clientRequestId()));
            SendMessageResponse response = projectRuntime.sendStreaming(
                    currentUser.id(),
                    projectId,
                    request.sessionId(),
                    new SendMessageRequest(request.content(), request.ragDisabled(), request.skillId(),
                            request.clientRequestId(), null),
                    canonicalContent -> {
                        if (StringUtils.hasText(canonicalContent)) {
                            sendSafe(session, WsChatEvent.chunk(
                                    request.sessionId(), canonicalContent, request.clientRequestId()));
                        }
                    },
                    process -> {
                        if (StringUtils.hasText(process)) {
                            sendSafe(session, WsChatEvent.process(
                                    request.sessionId(), process, request.clientRequestId()));
                        }
                    }
            );

            if (!response.success()) {
                if (response.debug() != null) {
                    sendSafe(session, WsChatEvent.debug(request.sessionId(), response.debug(), request.clientRequestId()));
                }
                log.warn("Project WebSocket runtime failed wsSessionId={} userId={} projectId={} chatSessionId={} clientRequestId={}",
                        session.getId(), currentUser.id(), projectId, request.sessionId(), request.clientRequestId());
                sendSafe(session, WsChatEvent.error(request.sessionId(),
                        clientError(response.errorMessage()), request.clientRequestId()));
                return;
            }

            if (response.debug() != null) {
                sendSafe(session, WsChatEvent.debug(request.sessionId(), response.debug(), request.clientRequestId()));
            }
            String finishReason = StringUtils.hasText(request.skillId()) ? "skill_langchain4j" : "langchain4j";
            sendSafe(session, WsChatEvent.projectDone(
                    request.sessionId(),
                    finishReason,
                    response.navigationUrl(),
                    request.clientRequestId(),
                    response.assistantContent(),
                    response.projectEvidence()
            ));
        } catch (Exception ex) {
            log.warn("Project WebSocket execution threw wsSessionId={} userId={} projectId={} chatSessionId={} clientRequestId={} errorType={}",
                    session.getId(), currentUser.id(), projectId, request.sessionId(), request.clientRequestId(),
                    ex.getClass().getSimpleName());
            sendSafe(session, WsChatEvent.error(
                    request.sessionId(), DEFAULT_WS_ERROR, request.clientRequestId()));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        JwtUser currentUser = currentUser(session);
        log.warn("Project WebSocket transport error wsSessionId={} userId={} projectId={} remote={} errorType={}",
                session.getId(), currentUser == null ? null : currentUser.id(), projectId(session),
                session.getRemoteAddress(), exception == null ? null : exception.getClass().getSimpleName());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        JwtUser currentUser = currentUser(session);
        log.debug("Project WebSocket closed wsSessionId={} userId={} projectId={} remote={} code={} reason={}",
                session.getId(), currentUser == null ? null : currentUser.id(), projectId(session),
                session.getRemoteAddress(), status == null ? null : status.getCode(),
                status == null ? null : status.getReason());
    }

    private JwtUser currentUser(WebSocketSession session) {
        Object value = session.getAttributes().get(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER);
        return value instanceof JwtUser user ? user : null;
    }

    private Long projectId(WebSocketSession session) {
        Object value = session.getAttributes().get(ProjectWebSocketHandshakeInterceptor.ATTR_PROJECT_ID);
        return value instanceof Number number ? number.longValue() : null;
    }

    private String clientError(String error) {
        if (!StringUtils.hasText(error)) {
            return DEFAULT_WS_ERROR;
        }
        String lower = error.toLowerCase(java.util.Locale.ROOT);
        if (error.matches("(?s).*[A-Za-z]:[\\\\/].*") || error.contains("\\\\")
                || lower.contains("file:/") || lower.contains("/home/") || lower.contains("/srv/")
                || lower.contains("/var/") || lower.contains("/opt/") || lower.contains("/tmp/")) {
            return DEFAULT_WS_ERROR;
        }
        return error;
    }

    private void send(WebSocketSession session, WsChatEvent event) throws IOException {
        if (session == null || !session.isOpen()) {
            return;
        }
        String payload = objectMapper.writeValueAsString(event);
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        }
    }

    private void sendSafe(WebSocketSession session, WsChatEvent event) {
        try {
            send(session, event);
        } catch (IOException | RuntimeException ex) {
            log.warn("Ignoring Project WebSocket send failure sessionId={} eventType={} errorType={}",
                    session == null ? null : session.getId(), event == null ? null : event.type(),
                    ex.getClass().getSimpleName());
        }
    }
}
