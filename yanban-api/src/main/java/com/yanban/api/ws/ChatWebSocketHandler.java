package com.yanban.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentService;
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

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final AgentService agentService;

    public ChatWebSocketHandler(ObjectMapper objectMapper,
                                AgentService agentService) {
        this.objectMapper = objectMapper;
        this.agentService = agentService;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JwtUser currentUser = (JwtUser) session.getAttributes().get(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER);
        if (currentUser == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unauthorized"));
            return;
        }

        WsChatRequest request = objectMapper.readValue(message.getPayload(), WsChatRequest.class);
        if (request.sessionId() == null || !StringUtils.hasText(request.content())) {
            sendSafe(session, WsChatEvent.error(request.sessionId(), "sessionId and content are required"));
            return;
        }

        try {
            SendMessageResponse response = agentService.sendMessageStreaming(
                    currentUser.id(),
                    request.sessionId(),
                    new SendMessageRequest(request.content(), request.ragDisabled(), request.skillId()),
                    token -> {
                        if (token != null && !token.isEmpty()) {
                            sendSafe(session, WsChatEvent.chunk(request.sessionId(), token));
                        }
                    }
            );

            if (!response.success()) {
                sendSafe(session, WsChatEvent.error(
                        request.sessionId(),
                        StringUtils.hasText(response.errorMessage()) ? response.errorMessage() : "对话处理失败"
                ));
                return;
            }

            if (StringUtils.hasText(response.navigationUrl())) {
                sendSafe(session, WsChatEvent.doneWithNavigation(request.sessionId(), "intent_redirect", response.navigationUrl()));
                return;
            }

            sendSafe(session, WsChatEvent.done(
                    request.sessionId(),
                    StringUtils.hasText(request.skillId()) ? "skill_harness" : "harness"
            ));
        } catch (Exception ex) {
            sendSafe(session, WsChatEvent.error(request.sessionId(), extractErrorMessage(ex)));
        }
    }

    private String extractErrorMessage(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return "对话处理失败";
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
            log.debug("Ignoring WebSocket send failure sessionId={} error={}",
                    session == null ? null : session.getId(),
                    ex.getMessage());
        }
    }
}
