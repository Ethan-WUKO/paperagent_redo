package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ToolCall;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentContextBuilderTest {

    private static final long SESSION_ID = 101L;
    private static final long USER_ID = 202L;

    @Mock
    private AgentMessageRepository messages;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AgentContextBuilder(messages, objectMapper);
    }

    @Test
    void buildsRuntimeIdentityOnlyForEmptySession() {
        when(messages.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of());

        AgentContextPackage context = builder.build(request(null, 8, 8_000));

        assertThat(context.rawMessageCount()).isZero();
        assertThat(context.normalizedMessageCount()).isZero();
        assertThat(context.messages()).hasSize(1);
        assertThat(context.messages().get(0).role()).isEqualTo("system");
        assertThat(context.messages().get(0).content()).contains("Runtime identity guard", "DeepSeek", "deepseek-chat");
        assertThat(context.sections()).extracting(AgentContextSection::type)
                .containsExactly("runtime_identity_guard");
        assertThat(context.droppedItems()).isEmpty();
    }

    @Test
    void keepsOnlyRecentMessagesWithinWindow() {
        when(messages.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of(
                message("user", "old question"),
                message("assistant", "middle answer"),
                message("user", "new question")
        ));

        AgentContextPackage context = builder.build(request(null, 2, 8_000));

        assertThat(context.messages()).extracting(ChatMessage::content)
                .contains("middle answer", "new question")
                .doesNotContain("old question");
        assertThat(context.messages()).extracting(ChatMessage::role)
                .containsExactly("assistant", "user", "system");
        assertThat(context.droppedItems()).anySatisfy(item -> {
            assertThat(item.type()).isEqualTo("message");
            assertThat(item.reason()).contains("recent message window");
        });
    }

    @Test
    void addsOptionalSessionSummaryBeforeRecentMessages() {
        when(messages.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of(
                message("user", "please remember my field"),
                message("assistant", "noted")
        ));

        AgentContextPackage context = builder.build(request("User studies retrieval augmented generation.", 4, 8_000));

        assertThat(context.messages()).extracting(ChatMessage::role)
                .containsExactly("system", "user", "assistant", "system");
        assertThat(context.messages().get(0).content()).contains("Session summary", "retrieval augmented generation");
        assertThat(context.sections()).extracting(AgentContextSection::type)
                .containsExactly("session_summary", "recent_messages", "runtime_identity_guard");
    }

    @Test
    void keepsOptionalRagAndToolTraceSectionsInStableOrder() {
        when(messages.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of(
                message("user", "follow up")
        ));

        AgentContextPackage context = builder.build(new AgentContextBuildRequest(
                SESSION_ID,
                USER_ID,
                "deepseek",
                "deepseek-chat",
                "User studies RAG.",
                "citation-1 says version filtering is active.",
                "latest literature task returned 3 papers.",
                4,
                8_000
        ));

        assertThat(context.sections()).extracting(AgentContextSection::type)
                .containsExactly("session_summary", "rag_context", "recent_messages", "tool_trace_context", "runtime_identity_guard");
        assertThat(context.messages()).extracting(ChatMessage::content)
                .contains(
                        "Session summary:\nUser studies RAG.",
                        "RAG context:\ncitation-1 says version filtering is active.",
                        "Tool trace context:\nlatest literature task returned 3 papers."
                );
    }

    @Test
    void downgradesHistoricalToolMessagesWithoutMatchingAssistantCall() {
        when(messages.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of(
                message("assistant", "I will call a tool", toolCallsJson("call-1"), null),
                message("tool", "orphan tool output", null, "other-call")
        ));

        AgentContextPackage context = builder.build(request(null, 8, 8_000));

        assertThat(context.messages()).noneSatisfy(message -> assertThat(message.role()).isEqualTo("tool"));
        assertThat(context.messages()).noneSatisfy(message -> assertThat(message.toolCalls()).isNotEmpty());
        assertThat(context.messages()).extracting(ChatMessage::content)
                .contains("I will call a tool", "Previous tool result:\norphan tool output");
    }

    @Test
    void preservesHistoricalAssistantToolCallWhenMatchingToolResultExists() {
        when(messages.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of(
                message("assistant", null, toolCallsJson("call-1"), null),
                message("tool", "tool output", null, "call-1")
        ));

        AgentContextPackage context = builder.build(request(null, 8, 8_000));

        assertThat(context.messages()).extracting(ChatMessage::role)
                .containsExactly("assistant", "tool", "system");
        assertThat(context.messages().get(0).toolCalls()).hasSize(1);
        assertThat(context.messages().get(1).toolCallId()).isEqualTo("call-1");
    }

    @Test
    void truncatesOversizedRecentMessageToStayInsideBudget() {
        String longMessage = "x".repeat(3_000);
        when(messages.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of(
                message("user", longMessage)
        ));

        AgentContextPackage context = builder.build(request(null, 8, 1_024));

        assertThat(context.messages()).extracting(ChatMessage::role)
                .containsExactly("user", "system");
        assertThat(context.messages().get(0).content()).endsWith("[truncated]");
        assertThat(context.messages().get(0).content().length()).isLessThan(longMessage.length());
        assertThat(context.droppedItems()).anySatisfy(item -> {
            assertThat(item.type()).isEqualTo("message_content");
            assertThat(item.reason()).contains("Truncated");
        });
    }

    private AgentContextBuildRequest request(String summary, Integer maxMessages, Integer maxCharacters) {
        return new AgentContextBuildRequest(
                SESSION_ID,
                USER_ID,
                "deepseek",
                "deepseek-chat",
                summary,
                maxMessages,
                maxCharacters
        );
    }

    private AgentMessage message(String role, String content) {
        return message(role, content, null, null);
    }

    private AgentMessage message(String role, String content, String toolCallsJson, String toolCallId) {
        return new AgentMessage(SESSION_ID, USER_ID, role, content, toolCallsJson, toolCallId, null);
    }

    private String toolCallsJson(String id) {
        try {
            return objectMapper.writeValueAsString(List.of(new ToolCall(
                    id,
                    "function",
                    new ToolCall.FunctionCall("search_knowledge", "{\"query\":\"memory\"}")
            )));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
