package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import com.yanban.paper.literature.AdHocLiteratureSearchService;
import com.yanban.paper.literature.AdHocLiteratureSearchService.AdHocLiteratureItem;
import com.yanban.paper.literature.AdHocLiteratureSearchService.AdHocLiteratureSearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_agent_controller_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class AgentControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AgentMessageRepository agentMessages;

    @MockBean(name = "chatModelProvider")
    ChatModelProvider chatModelProvider;

    @MockBean
    AdHocLiteratureSearchService adHocLiteratureSearchService;

    @Test
    void createSessionSendMessageAndListPersistedMessages() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any())).thenReturn(
                new ChatResponse(ChatMessage.assistant("你好，我是研伴。"), "stop", null)
        );
        String token = registerAndGetToken("agent_user_a");

        long sessionId = createSession(token, "测试会话");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.assistantContent").value("你好，我是研伴。"))
                .andExpect(jsonPath("$.messages.length()").value(2));

        MvcResult messagesResult = mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("你好"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].content").value("你好，我是研伴。"))
                .andReturn();

        JsonNode messages = objectMapper.readTree(messagesResult.getResponse().getContentAsString());
        assertThat(messages.get(0).get("sessionId").asLong()).isEqualTo(sessionId);
        assertThat(messages.get(1).get("sessionId").asLong()).isEqualTo(sessionId);
    }

    @Test
    void runtimeIdentityQuestionUsesConfiguredProviderWithoutCallingModel() throws Exception {
        String token = registerAndGetToken("agent_user_identity");
        long sessionId = createSession(token, "Identity");

        mockMvc.perform(patch("/api/v1/agent/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelProvider\":\"glm\",\"model\":\"glm-4.5-air\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\\u4f60\\u662f\\u4ec0\\u4e48\\u6a21\\u578b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.steps").value(0))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("GLM")))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("glm-4.5-air")))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("provider="))))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("model="))))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Runtime identity guard"))))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("backend"))))
                .andExpect(jsonPath("$.messages.length()").value(2));

        verify(chatModelProvider, times(0)).chat(any());
    }

    @Test
    void ordinaryMessagesIncludeRuntimeIdentityGuardButDoNotPersistIt() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("guarded answer"), "stop", null));
        String token = registerAndGetToken("agent_user_identity_guard");
        long sessionId = createSession(token, "Guard");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.messages.length()").value(2));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider).chat(captor.capture());
        assertThat(captor.getValue().messages())
                .anySatisfy(message -> {
                    assertThat(message.role()).isEqualTo("system");
                    assertThat(message.content()).contains("Runtime identity guard");
                    assertThat(message.content()).contains("user-visible model identity");
                    assertThat(message.content()).contains("DeepSeek");
                });

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages?view=all&limit=10", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].role").value("assistant"));
    }

    @Test
    void commonKnowledgeQuestionDoesNotExposeToolsToModel() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("Watermelon is hydrating."), "stop", null));
        String token = registerAndGetToken("agent_user_common_knowledge");
        long sessionId = createSession(token, "Common Knowledge");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\\u897f\\u74dc\\u7684\\u529f\\u6548\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.steps").value(1))
                .andExpect(jsonPath("$.assistantContent").value("Watermelon is hydrating."))
                .andExpect(jsonPath("$.messages.length()").value(2));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider).chat(captor.capture());
        assertThat(captor.getValue().tools()).isNull();
    }

    @Test
    void modelFailurePersistsUserMessageAndVisibleError() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any())).thenThrow(new IllegalStateException("mock model failed"));
        String token = registerAndGetToken("agent_user_model_failure");
        long sessionId = createSession(token, "Failure");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"please fail\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("mock model failed")))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"));

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("please fail"))
                .andExpect(jsonPath("$[1].content").value(org.hamcrest.Matchers.containsString("mock model failed")));
    }

    @Test
    void persistedToolMessagesKeepToolCallIdInNextModelRequest() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(
                        new ChatMessage("assistant", null, List.of(new ToolCall(
                                "call-1",
                                "function",
                                new ToolCall.FunctionCall("echo", "{\"message\":\"hello\"}")
                        )), null),
                        "tool_calls",
                        null
                ))
                .thenReturn(new ChatResponse(ChatMessage.assistant("tool done"), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("second done"), "stop", null));
        String token = registerAndGetToken("agent_user_tool_call_id");
        long sessionId = createSession(token, "Tool History");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"please call echo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages?view=all&limit=10", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].role").value("tool"))
                .andExpect(jsonPath("$[2].toolCallId").value("call-1"));

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"continue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider, times(3)).chat(captor.capture());

        ChatRequest secondUserRequest = captor.getAllValues().get(2);
        assertThat(secondUserRequest.messages())
                .filteredOn(message -> "tool".equals(message.role()))
                .singleElement()
                .extracting(ChatMessage::toolCallId)
                .isEqualTo("call-1");
    }

    @Test
    void invalidHistoricalToolMessagesAreDowngradedBeforeModelRequest() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("second done"), "stop", null));
        String token = registerAndGetToken("agent_user_invalid_tool_history");
        long userId = currentUserId(token);
        long sessionId = createSession(token, "Invalid Tool History");

        String toolCallsJson = objectMapper.writeValueAsString(List.of(new ToolCall(
                "call-old",
                "function",
                new ToolCall.FunctionCall("echo", "{\"message\":\"hello\"}")
        )));
        agentMessages.save(new AgentMessage(sessionId, userId, "user", "old tool request", null, null));
        agentMessages.save(new AgentMessage(sessionId, userId, "assistant", null, toolCallsJson, null, null));
        agentMessages.save(new AgentMessage(sessionId, userId, "tool", "{\"message\":\"hello\"}", null, null, null));

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"continue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider).chat(captor.capture());

        ChatRequest request = captor.getValue();
        assertThat(request.messages())
                .noneMatch(message -> "tool".equals(message.role()));
        assertThat(request.messages())
                .filteredOn(message -> "assistant".equals(message.role()))
                .allMatch(message -> message.toolCalls() == null || message.toolCalls().isEmpty());
        assertThat(request.messages())
                .filteredOn(message -> "assistant".equals(message.role()))
                .extracting(ChatMessage::content)
                .anyMatch(content -> content != null && content.contains("Previous tool result"));
    }

    @Test
    void userCannotAccessAnotherUsersSession() throws Exception {
        String tokenA = registerAndGetToken("agent_user_owner");
        String tokenB = registerAndGetToken("agent_user_other");
        long sessionId = createSession(tokenA, "私有会话");

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"越权访问\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void paperRevisionIntentReturnsPaperLink() throws Exception {
        String token = registerAndGetToken("agent_user_paper_intent");
        long sessionId = createSession(token, "论文会话");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"帮我润色论文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navigationUrl").value("/paper"))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("/paper")));

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].content").value(org.hamcrest.Matchers.containsString("/paper")));
    }

    @Test
    void literatureIntentReturnsSearchResultsWithoutCallingChatModel() throws Exception {
        when(adHocLiteratureSearchService.search(eq("FDA-MIMO jamming"), anyInt(), any()))
                .thenReturn(new AdHocLiteratureSearchResult("FDA-MIMO jamming", List.of(
                        new AdHocLiteratureItem(
                                "FDA-MIMO Radar for Mainlobe Jamming Suppression",
                                List.of("Alice Zhang"),
                                2024,
                                "IEEE TAES",
                                "10.1109/demo",
                                null,
                                "W1",
                                "https://doi.org/10.1109/demo",
                                "This paper studies FDA-MIMO jamming suppression.",
                                "openalex",
                                "FDA-MIMO jamming",
                                0.91,
                                "@article{zhang2024fda,\n  title={FDA-MIMO Radar for Mainlobe Jamming Suppression}\n}\n"
                        )
                ), 1, 1, 1, List.of()));
        String token = registerAndGetToken("agent_user_literature_intent");
        long sessionId = createSession(token, "文献会话");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"/literature FDA-MIMO jamming 1篇 bibtex\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.navigationUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("FDA-MIMO Radar")))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("```bibtex")))
                .andExpect(jsonPath("$.messages.length()").value(2));
    }

    @Test
    void firstMessageAutoGeneratesSessionTitle() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("主回答"), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("阅读 Java 文件"), "stop", null));
        String token = registerAndGetToken("agent_user_auto_title");
        long sessionId = createSession(token, "新会话");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"请帮我阅读 ChatWebSocketHandler.java\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sessionId))
                .andExpect(jsonPath("$[0].title").value("阅读 Java 文件"));
    }

    @Test
    void updateSessionCanRenameChangeModelAndDelete() throws Exception {
        String token = registerAndGetToken("agent_user_update_delete");
        long sessionId = createSession(token, "旧标题");

        mockMvc.perform(patch("/api/v1/agent/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\",\"modelProvider\":\"glm\",\"model\":\"glm-4.5-air\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新标题"))
                .andExpect(jsonPath("$.modelProvider").value("glm"))
                .andExpect(jsonPath("$.model").value("glm-4.5-air"));

        mockMvc.perform(delete("/api/v1/agent/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void listSessionsOnlyReturnsCurrentUsersSessions() throws Exception {
        String tokenA = registerAndGetToken("agent_user_list_a");
        String tokenB = registerAndGetToken("agent_user_list_b");
        createSession(tokenA, "A 的会话");
        createSession(tokenB, "B 的会话");

        mockMvc.perform(get("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("A 的会话"));
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long createSession(String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"maxSteps\":20}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long currentUserId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
