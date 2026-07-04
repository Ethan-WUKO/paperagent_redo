package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentSession;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PlanStepVerifierTest {

    @Mock
    ChatModelProvider modelProvider;

    private PlanStepVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new PlanStepVerifier(modelProvider, new ObjectMapper());
    }

    @Test
    void verifyReturnsPassedAndSendsCompleteVerificationContext() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("""
                        {
                          "passed": true,
                          "reason": "The candidate includes the required artifact.",
                          "evidence": "artifact path and summary are present",
                          "missingItems": []
                        }
                        """),
                "stop",
                null
        ));

        PlanStepVerifier.VerificationResult result = verifier.verify(newRequest("candidate artifact path: report.md"));

        assertThat(result.passed()).isTrue();
        assertThat(result.conclusive()).isTrue();
        assertThat(result.reason()).contains("required artifact");
        assertThat(result.evidence()).contains("artifact path");

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(modelProvider).chat(requestCaptor.capture());
        ChatRequest request = requestCaptor.getValue();
        assertThat(request.provider()).isEqualTo(UserSettingsService.DEFAULT_PROVIDER);
        assertThat(request.model()).isEqualTo(UserSettingsService.DEFAULT_DEEPSEEK_MODEL);
        assertThat(request.temperature()).isEqualTo(0.0);
        assertThat(request.maxTokens()).isEqualTo(768);
        assertThat(request.responseFormat()).isEqualTo(ChatRequest.ResponseFormat.jsonObject());
        assertThat(request.thinking()).isEqualTo(ChatRequest.Thinking.disabled());
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(1).content())
                .contains("Overall goal")
                .contains("Complete the research task")
                .contains("success criteria")
                .contains("The step has produced a usable result")
                .contains("dependency result")
                .contains("candidate artifact path");
    }

    @Test
    void verifyReturnsFailureWithMissingItems() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("""
                        {
                          "passed": false,
                          "reason": "The candidate is too vague",
                          "missingItems": ["specific source", "reusable conclusion"]
                        }
                        """),
                "stop",
                null
        ));

        PlanStepVerifier.VerificationResult result = verifier.verify(newRequest("looks fine"));

        assertThat(result.passed()).isFalse();
        assertThat(result.conclusive()).isTrue();
        assertThat(result.reason())
                .contains("too vague")
                .contains("specific source")
                .contains("reusable conclusion");
    }

    @Test
    void verifyIsInconclusiveWhenModelReturnsMalformedJson() {
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("not json"),
                "stop",
                null
        ));

        PlanStepVerifier.VerificationResult result = verifier.verify(newRequest("candidate result"));

        assertThat(result.passed()).isTrue();
        assertThat(result.conclusive()).isFalse();
        assertThat(result.reason()).contains("invalid JSON");
    }

    private PlanStepVerifier.VerificationRequest newRequest(String candidateResult) {
        AgentPlan plan = new AgentPlan(
                11L,
                7L,
                "Complete the research task",
                "Research task plan",
                false,
                null,
                "{}"
        );
        ReflectionTestUtils.setField(plan, "id", 19L);

        AgentSession session = new AgentSession(
                7L,
                "Verifier test session",
                UserSettingsService.DEFAULT_PROVIDER,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                8,
                false
        );
        ReflectionTestUtils.setField(session, "id", 11L);

        AgentPlanStep dependency = new AgentPlanStep(
                19L,
                "step_1",
                1,
                "Dependency",
                "Produce dependency result",
                "ANALYSIS",
                "[]",
                "[]",
                "Dependency result exists."
        );
        dependency.markCompleted("dependency result");

        AgentPlanStep step = new AgentPlanStep(
                19L,
                "step_2",
                2,
                "Current",
                "Produce the final artifact",
                "VERIFICATION",
                "[\"step_1\"]",
                "[]",
                "The step has produced a usable result."
        );

        return new PlanStepVerifier.VerificationRequest(
                plan,
                session,
                step,
                List.of(dependency, step),
                candidateResult,
                "test-api-key"
        );
    }
}
