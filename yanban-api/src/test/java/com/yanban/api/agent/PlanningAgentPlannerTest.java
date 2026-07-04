package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanningAgentPlannerTest {

    @Mock
    ChatModelProvider modelProvider;

    private PlanningAgentPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new PlanningAgentPlanner(modelProvider, new ToolRegistry(), new ObjectMapper());
    }

    @Test
    void createPlanUsesStructuredLowLatencyRequest() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {
                  "summary": "Study RAG",
                  "steps": [
                    {
                      "id": "research",
                      "title": "Research RAG basics",
                      "description": "Summarize core RAG principles.",
                      "type": "ANALYSIS",
                      "dependencies": [],
                      "allowedTools": [],
                      "successCriteria": "Core principles are summarized."
                    }
                  ]
                }
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Learn RAG in two weeks.",
                "glm",
                "glm-4.5-air",
                "test-key",
                null,
                null,
                null
        );

        assertThat(plan.summary()).isEqualTo("Study RAG");
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).id()).isEqualTo("step_1");

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(requestCaptor.capture());
        ChatRequest request = requestCaptor.getValue();
        assertThat(request.provider()).isEqualTo("glm");
        assertThat(request.model()).isEqualTo("glm-4.5-air");
        assertThat(request.maxTokens()).isEqualTo(1536);
        assertThat(request.responseFormat()).isEqualTo(ChatRequest.ResponseFormat.jsonObject());
        assertThat(request.thinking()).isEqualTo(ChatRequest.Thinking.disabled());
        assertThat(request.messages().get(0).content()).contains("Return one JSON object only");
    }

    @Test
    void createPlanFallsBackWhenModelCallFails() {
        when(modelProvider.chat(any())).thenThrow(new RuntimeException("Timeout on blocking read"));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Create a launch checklist.",
                "glm",
                "glm-4.5-air",
                "test-key",
                null,
                null,
                null
        );

        assertThat(plan.summary()).contains("Direct execution");
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).description()).isEqualTo("Create a launch checklist.");
        assertThat(plan.rawJson()).contains("Planner model call failed");
    }
}
