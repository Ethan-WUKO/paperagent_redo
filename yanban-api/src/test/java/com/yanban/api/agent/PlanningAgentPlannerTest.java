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
import java.util.List;
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
        planner = new PlanningAgentPlanner(modelProvider, new ObjectMapper());
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
        assertThat(request.messages().get(0).content())
                .contains("use [] when the step must not receive any tool")
                .contains("Tools exposed to this plan:\n");
    }

    @Test
    void createPlanReturnsExplicitFailureWhenModelCallFails() {
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

        assertThat(plan.failureCode()).isEqualTo(PlannerFailureCode.MODEL_CALL_FAILED);
        assertThat(plan.failureMessage()).contains("Planner model call failed");
        assertThat(plan.executable()).isFalse();
        assertThat(plan.steps()).isEmpty();
    }

    @Test
    void plannerFailuresAreClassifiedWithoutExecutableFallback() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(""), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("not json"), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("{\"summary\":\"none\",\"steps\":[]}"), "stop", null));

        PlanningAgentPlanner.PlanSpec empty = createPlan();
        PlanningAgentPlanner.PlanSpec invalid = createPlan();
        PlanningAgentPlanner.PlanSpec noSteps = createPlan();

        assertThat(empty.failureCode()).isEqualTo(PlannerFailureCode.EMPTY_RESPONSE);
        assertThat(invalid.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
        assertThat(noSteps.failureCode()).isEqualTo(PlannerFailureCode.NO_STEPS);
        assertThat(empty.steps()).isEmpty();
        assertThat(invalid.steps()).isEmpty();
        assertThat(noSteps.steps()).isEmpty();
    }

    private PlanningAgentPlanner.PlanSpec createPlan() {
        return planner.createPlan("Create a launch checklist.", "glm", "glm-4.5-air", "test-key", null, null, null);
    }

    @Test
    void explicitCamelCaseDenyAllCannotBeOverriddenByLegacyAlias() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {
                  "summary": "Conflict",
                  "steps": [{
                    "id": "step_1",
                    "title": "No tools",
                    "description": "Answer directly.",
                    "type": "ANALYSIS",
                    "dependencies": [],
                    "allowedTools": [],
                    "allowed_tools": ["search_web"],
                    "successCriteria": "Answered without tools."
                  }]
                }
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Explain the architecture.", "glm", "glm-4.5-air", "test-key", null, null, List.of("search_web"));

        assertThat(plan.steps()).singleElement().satisfies(step -> assertThat(step.allowedTools()).isEmpty());
    }
}
