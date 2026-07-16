package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
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
        assertThat(request.maxTokens()).isEqualTo(3072);
        assertThat(request.tools()).isNull();
        assertThat(request.responseFormat()).isEqualTo(ChatRequest.ResponseFormat.jsonObject());
        assertThat(request.thinking()).isEqualTo(ChatRequest.Thinking.disabled());
        assertThat(request.messages().get(0).content()).contains("Return one JSON object only");
        assertThat(request.messages().get(0).content())
                .contains("use [] when the step must not receive any tool")
                .contains("Tools exposed to this plan:\n")
                .contains("at most 6 for complex tasks")
                .contains("description <= 240");
    }

    @Test
    void createPlanReceivesBoundedCrossMaterialRequirementsInSystemPromptOnly() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {"summary":"Audit materials","steps":[{
                  "id":"audit","title":"Audit paper and code",
                  "description":"Inspect governed paper and code observations.","type":"ANALYSIS",
                  "dependencies":[],"allowedTools":["project_latex_outline","project_code_symbols"],
                  "successCriteria":"Paper and code observations are cited."}]}
                """), "stop", null));
        AgentOrchestrationRequirements requirements = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK,
                        AgentStrategySignal.VERIFICATION_REQUIRED),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN),
                List.of(
                        new ResearchMaterialRequirement(ResearchMaterialKind.PAPER_LATEX,
                                List.of("project_latex_outline", "project_read_file"),
                                List.of("project_latex_outline"), true),
                        new ResearchMaterialRequirement(ResearchMaterialKind.CODE,
                                List.of("project_code_symbols", "project_read_file"),
                                List.of("project_code_symbols"), true)));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Audit the implementation claims.", "test", "model", "key", "url", null,
                List.of("project_latex_outline", "project_code_symbols"), requirements);

        assertThat(plan.executable()).isTrue();
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(requestCaptor.capture());
        ChatRequest request = requestCaptor.getValue();
        assertThat(request.messages().get(0).content())
                .contains("Server-attested bounded research orchestration requirements")
                .contains("Cover PAPER_LATEX using only one or more of: project_latex_outline")
                .contains("Include a verification step")
                .contains("do not add tools, permissions, identity, network, command, or write authority");
        assertThat(request.messages().get(1).content())
                .isEqualTo("Create an executable plan for this user task:\nAudit the implementation claims.");
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
                .thenReturn(new ChatResponse(ChatMessage.assistant(""), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("not json"), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("still not json"), "stop", null))
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

    @Test
    void truncatedFirstAttemptIsReplannedOnceWithSameEndpointAndAllowlist() {
        String truncated = """
                {
                  "summary": "Inspect Project",
                  "steps": [{
                    "id": "step_1",
                    "title": "Inspect",
                    "description": "Read the selected Project file
                """;
        String valid = """
                {
                  "summary": "Inspect Project safely",
                  "steps": [{
                    "id": "inspect",
                    "title": "Inspect source",
                    "description": "Read the authorized Project source and summarize relevant findings.",
                    "type": "FILE_READ",
                    "dependencies": [],
                    "allowedTools": ["project_read_file", "write_file"],
                    "successCriteria": "Findings cite the authorized Project source."
                  }]
                }
                """;
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(truncated), "length", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(valid), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = planner.createPlan(
                "Inspect the Project source.", "deepseek", "deepseek-v4-flash",
                "test-key", "https://api.example.test", "read-only Project",
                List.of("project_read_file"));

        assertThat(plan.executable()).isTrue();
        assertThat(plan.steps()).singleElement().satisfies(step ->
                assertThat(step.allowedTools()).containsExactly("project_read_file"));

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().get(0).maxTokens()).isEqualTo(3072);
        assertThat(requests.getAllValues().get(1).maxTokens()).isEqualTo(2048);
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.provider()).isEqualTo("deepseek");
            assertThat(request.model()).isEqualTo("deepseek-v4-flash");
            assertThat(request.apiKey()).isEqualTo("test-key");
            assertThat(request.apiUrl()).isEqualTo("https://api.example.test");
            assertThat(request.tools()).isNull();
            assertThat(request.responseFormat()).isEqualTo(ChatRequest.ResponseFormat.jsonObject());
            assertThat(request.thinking()).isEqualTo(ChatRequest.Thinking.disabled());
            assertThat(request.messages().get(0).content()).contains("project_read_file");
        });
        assertThat(requests.getAllValues().get(1).messages().get(0).content())
                .contains("compact JSON object")
                .contains("exact resolved allowlist below")
                .doesNotContain(truncated)
                .contains("1-4 short steps")
                .contains("\"deps\"")
                .contains("\"tools\"");
    }

    @Test
    void truncatedRepairAttemptDoesNotGetAcceptedOrRetriedAgain() {
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("{\"summary\":\"x\",\"steps\":[{\"id\":\"s1\""), "length", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("{\"summary\":\"y\",\"steps\":[{\"id\":\"s1\""), "length", null));

        PlanningAgentPlanner.PlanSpec plan = createPlan();

        assertThat(plan.executable()).isFalse();
        assertThat(plan.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
        assertThat(plan.failureMessage()).contains("after one bounded retry", "first=INVALID_PLAN", "second=INVALID_PLAN");
        verify(modelProvider, times(2)).chat(any());
    }

    @Test
    void twoInvalidAttemptsFailDeterministicallyWithoutLeakingRawOutput() {
        String unboundedRaw = "not-json-" + "private-output-".repeat(1000);
        when(modelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant(unboundedRaw), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(unboundedRaw), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = createPlan();

        verify(modelProvider, times(2)).chat(any());
        assertThat(plan.executable()).isFalse();
        assertThat(plan.failureCode()).isEqualTo(PlannerFailureCode.INVALID_PLAN);
        assertThat(plan.failureMessage())
                .contains("after one bounded retry", "first=INVALID_PLAN", "second=INVALID_PLAN")
                .doesNotContain("Raw output", "private-output-private-output-private-output");
        assertThat(plan.failureMessage().length()).isLessThanOrEqualTo(500);
        assertThat(plan.steps()).isEmpty();
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
