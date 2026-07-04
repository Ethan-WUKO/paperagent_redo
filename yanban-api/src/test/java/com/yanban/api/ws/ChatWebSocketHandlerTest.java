package com.yanban.api.ws;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentService;
import com.yanban.api.agent.SendMessageResponse;
import com.yanban.api.security.JwtUser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class ChatWebSocketHandlerTest {

    @Test
    void sendsChunkAndDoneEventsThroughHarnessPath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentService agentService = Mockito.mock(AgentService.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(objectMapper, agentService);

        when(agentService.sendMessageStreaming(Mockito.eq(1001L), Mockito.eq(99L), any(), Mockito.<Consumer<String>>any()))
                .thenAnswer(invocation -> {
                    Consumer<String> tokenConsumer = invocation.getArgument(3);
                    tokenConsumer.accept("你");
                    tokenConsumer.accept("好");
                    return new SendMessageResponse(true, "你好", 1, null, null, List.of());
                });

        WebSocketSession wsSession = Mockito.mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER, new JwtUser(1001L, "alice"));
        when(wsSession.getAttributes()).thenReturn(attributes);
        when(wsSession.isOpen()).thenReturn(true);
        List<String> outbound = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            outbound.add(msg.getPayload());
            return null;
        }).when(wsSession).sendMessage(any(TextMessage.class));

        handler.handleTextMessage(wsSession, new TextMessage("{\"sessionId\":99,\"content\":\"你好\"}"));

        assertThat(outbound).hasSize(3);
        assertThat(outbound.get(0)).contains("\"type\":\"chunk\"").contains("\"content\":\"你\"");
        assertThat(outbound.get(1)).contains("\"type\":\"chunk\"").contains("\"content\":\"好\"");
        assertThat(outbound.get(2)).contains("\"type\":\"done\"").contains("\"finishReason\":\"harness\"");
    }

    @Test
    void sendSafeDoesNotPropagateRuntimeWebSocketSendFailure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentService agentService = Mockito.mock(AgentService.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(objectMapper, agentService);

        when(agentService.sendMessageStreaming(Mockito.eq(1001L), Mockito.eq(99L), any(), Mockito.<Consumer<String>>any()))
                .thenThrow(new RuntimeException("Encoding error [MALFORMED[1]]"));

        WebSocketSession wsSession = Mockito.mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER, new JwtUser(1001L, "alice"));
        when(wsSession.getAttributes()).thenReturn(attributes);
        when(wsSession.isOpen()).thenReturn(true);
        Mockito.doThrow(new IllegalStateException("TEXT_PARTIAL_WRITING"))
                .when(wsSession).sendMessage(any(TextMessage.class));

        assertThatCode(() -> handler.handleTextMessage(wsSession, new TextMessage("{\"sessionId\":99,\"content\":\"你好\"}")))
                .doesNotThrowAnyException();
    }
}
