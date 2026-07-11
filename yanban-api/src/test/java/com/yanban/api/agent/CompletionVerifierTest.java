package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.model.ChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class CompletionVerifierTest {
    private final ProjectService projects = mock(ProjectService.class);
    private final CandidateChangeArtifactService candidates = mock(CandidateChangeArtifactService.class);
    private final CompletionVerifier verifier = new CompletionVerifier(new ObjectMapper(), new ProjectEvidenceValidator(projects), candidates);

    CompletionVerifierTest() {
        when(projects.manifest(anyLong(), anyLong())).thenReturn(new ProjectManifestResponse(42L, "m", List.of(
                new ProjectFileEntry("src/Main.java", 1, Instant.EPOCH, "h1"))));
        when(candidates.store(anyLong(), anyLong(), any())).thenAnswer(call -> call.getArgument(2));
    }

    @Test
    void projectClaimWithoutCurrentFileEvidenceCannotBeVerified() {
        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), success("已全面审查所有文件", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.assistantContent()).isEqualTo(
                "Insufficient Project evidence: no authorized file read/search observation was captured; this is not a complete review.");
        assertThat(result.messages()).filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .singleElement().extracting(ChatMessage::content).isEqualTo(result.assistantContent());
    }

    @Test
    void currentAuthorizedProjectEvidenceVerifiesAndEmitsNotAppliedCandidate() {
        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), success("建议修复空指针", List.of(tool(42, "src/Main.java", "h1"))));

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("VERIFIED");
        assertThat(result.candidateChangeSet()).isNull();
    }

    @Test
    void historicalCrossProjectAndWrongHashEvidenceCannotPass() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT);
        AgentRuntimeResult historicalOnly = verifier.verify(request, success("review", List.of()));
        EvidenceLedger foreign = new EvidenceLedger(List.of(new EvidenceRef("trusted-tool:99:src/Main.java:h1:c1",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:c1", null, "h1", "test")));
        AgentRuntimeResult foreignResult = verifier.verify(request, success("review", List.of()).withEvidenceLedger(foreign));
        EvidenceLedger wrongHash = new EvidenceLedger(List.of(new EvidenceRef("trusted-tool:42:src/Main.java:old:c1",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:c1", null, "old", "test")));
        CandidateChangeSet candidate = new CandidateChangeSet(42L, "src/Main.java", "h1", "s", "p", List.of(), null, null);

        assertThat(historicalOnly.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(foreignResult.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(candidate.revalidate(wrongHash).status()).isEqualTo(CandidateChangeStatus.STALE);
    }

    @Test
    void rawLedgerCannotBypassTrustedCurrentObservationRequirement() {
        EvidenceLedger forgedCurrent = new EvidenceLedger(List.of(new EvidenceRef("trusted-tool:42:src/Main.java:h1:forged",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:forged", null, "h1", "forged")));

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("claimed", List.of()).withEvidenceLedger(forgedCurrent));

        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void reflectionExecutesAtMostOneRepairTurn() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest request) {
                calls.incrementAndGet();
                List<ChatMessage> runtimeMessages = new ArrayList<>(request.history());
                if (calls.get() > 1) runtimeMessages.add(tool(42, "src/Main.java", "h1"));
                return success(calls.get() == 1 ? "claimed complete" : "repaired", runtimeMessages);
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier, new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult result = runtime.run(projectRequest(AgentStrategy.SINGLE_STEP_REACT));

        assertThat(calls).hasValue(2);
        assertThat(result.completionVerification().reflectionAttempts()).isEqualTo(1);
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    @Test
    void manifestOnlyAttemptRepairsWithReadSearchInstructionAndKeepsOneFinalTranscript() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AgentRuntimeRequest> capturedRepair = new AtomicReference<>();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest request) {
                int call = calls.incrementAndGet();
                List<ChatMessage> runtimeMessages = new ArrayList<>(request.history());
                if (call == 1) {
                    runtimeMessages.add(new ChatMessage("tool",
                            "{\"projectId\":42,\"relativePath\":\"manifest\",\"hash\":\"m\",\"version\":\"m\"}",
                            null, "manifest-call"));
                    runtimeMessages.add(ChatMessage.assistant("Unverified manifest-only answer."));
                    return new AgentRuntimeResult(true, "Unverified manifest-only answer.", runtimeMessages, 1, null,
                            List.of("step=1 tool=project_manifest success=true"), List.of(), 2, 3, 5);
                }
                capturedRepair.set(request);
                runtimeMessages.add(tool(42, "src/Main.java", "h1"));
                runtimeMessages.add(ChatMessage.assistant("Repaired file-grounded answer."));
                return new AgentRuntimeResult(true, "Repaired file-grounded answer.", runtimeMessages, 1, null,
                        List.of("step=1 tool=project_read_file success=true"), List.of(), 4, 5, 9);
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier,
                new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult result = runtime.run(projectRequest(AgentStrategy.SINGLE_STEP_REACT));

        assertThat(calls).hasValue(2);
        assertThat(capturedRepair.get().history()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).contains("project_read_file", "project_search", "project_manifest");
        });
        assertThat(capturedRepair.get().projectContext()).isEqualTo(new ProjectRuntimeContext(7L, 42L));
        assertThat(capturedRepair.get().toolPolicy().allowedTools())
                .isEqualTo(projectRequest(AgentStrategy.SINGLE_STEP_REACT).toolPolicy().allowedTools());
        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Repaired file-grounded answer.");
        assertThat(result.messages()).noneMatch(message -> "Unverified manifest-only answer.".equals(message.content()));
        assertThat(result.messages()).filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .singleElement().extracting(ChatMessage::content).isEqualTo("Repaired file-grounded answer.");
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.promptTokens()).isEqualTo(6);
        assertThat(result.completionTokens()).isEqualTo(8);
        assertThat(result.totalTokens()).isEqualTo(14);
        assertThat(result.trustedEvidenceLedger().evidence()).singleElement()
                .extracting(EvidenceRef::file).isEqualTo("src/Main.java");
    }

    @Test
    void exactDuplicateCurrentEvidenceIsDeduplicatedWithoutClearingResult() {
        ChatMessage observation = tool(42, "src/Main.java", "h1");

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("Observed once.", List.of(observation, observation)));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Observed once.");
        assertThat(result.trustedEvidenceLedger().evidence()).singleElement()
                .extracting(EvidenceRef::id).asString().contains("trusted-tool:42:src/Main.java:h1:call-1");
    }

    @Test
    void repairDoesNotReclassifyRepeatedHistoryAsCurrentEvidence() {
        ChatMessage historicalAssistant = ChatMessage.assistant("previous turn answer");
        ChatMessage historical = tool(42, "src/Main.java", "h1");
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT,
                List.of(historicalAssistant, historical));
        AtomicInteger calls = new AtomicInteger();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest current) {
                calls.incrementAndGet();
                List<ChatMessage> runtimeMessages = new ArrayList<>(current.history());
                runtimeMessages.add(new ChatMessage("tool",
                        "{\"projectId\":42,\"relativePath\":\"manifest\",\"hash\":\"m\",\"version\":\"m\"}",
                        null, "same-manifest-call"));
                runtimeMessages.add(ChatMessage.assistant("Manifest-only claim."));
                return new AgentRuntimeResult(true, "Manifest-only claim.", runtimeMessages, 1, null,
                        List.of("step=1 tool=project_manifest success=true"), List.of(), null, null, null);
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier,
                new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult result = runtime.run(request);

        assertThat(calls).hasValue(2);
        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.trustedEvidenceLedger().evidence()).isEmpty();
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.fallbacks()).noneMatch(value -> value.contains("duplicate evidence id"));
        assertThat(result.messages()).filteredOn(message -> historicalAssistant.equals(message)).hasSize(1);
        assertThat(result.messages()).filteredOn(message -> historical.equals(message)).hasSize(1);
        assertThat(result.messages()).filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .hasSize(2);
        assertThat(AgentService.runtimeMessagesToPersist(result.messages(), 2))
                .filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .singleElement().extracting(ChatMessage::content).isEqualTo(result.assistantContent());
    }

    @Test
    void reflectionPreservesAuthorityBudgetAndProjectIdentity() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT);

        assertThat(new CompletionReflection().preserveAuthority(request)).isSameAs(request);
    }

    @Test
    void projectDenyAllAndToolBudgetExhaustionDoNotRunRepair() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest ignored) { calls.incrementAndGet(); return success("claim", List.of()); }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier, new CompletionReflection(), new AdapterCompletionRepairExecutor());
        AgentRuntimeRequest denied = new AgentRuntimeRequest(AgentStrategy.SINGLE_STEP_REACT, 1L, List.of(), 7L, "inspect",
                "test", "model", null, null, 2, true, "skill", null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, new ResolvedToolPolicy(List.of(), 0, 0, "deny"),
                null, null, "trace", null, null).withProjectContext(new ProjectRuntimeContext(7L, 42L));

        runtime.run(denied);

        assertThat(calls).hasValue(1);
    }

    @Test
    void repairRequestRetainsAuthorityAndOnlyReducesBudgets() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT);
        AgentRuntimeRequest repair = new CompletionReflection().repairRequest(request, success("first", List.of()));

        assertThat(repair.projectContext()).isEqualTo(request.projectContext());
        assertThat(repair.provider()).isEqualTo(request.provider());
        assertThat(repair.model()).isEqualTo(request.model());
        assertThat(repair.skillId()).isEqualTo(request.skillId());
        assertThat(repair.toolPolicy().allowedTools()).isEqualTo(request.toolPolicy().allowedTools());
        assertThat(repair.maxSteps()).isLessThanOrEqualTo(request.maxSteps());
        assertThat(repair.toolPolicy().maxToolCalls()).isLessThanOrEqualTo(request.toolPolicy().maxToolCalls());
    }

    @Test
    void explicitModificationIntentPersistsCandidateWhileReadOnlyIntentDoesNot() {
        AgentRuntimeRequest modify = new AgentRuntimeRequest(AgentStrategy.SINGLE_STEP_REACT, 1L, List.of(), 7L, "suggest patch",
                "test", "model", null, null, 2, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, new ResolvedToolPolicy(List.of("project_read_file"), 2, 1, "project"),
                null, null, "trace", null, null).withProjectContext(new ProjectRuntimeContext(7L, 42L));

        AgentRuntimeResult result = verifier.verify(modify, success("patch", List.of(tool(42, "src/Main.java", "h1"))));

        assertThat(result.candidateChangeSet()).isNotNull();
        verify(candidates).store(anyLong(), anyLong(), any());
    }

    @Test
    void reactAndPlanExecuteBothCrossTheSameVerificationGate() {
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) {
                return strategy == AgentStrategy.SINGLE_STEP_REACT || strategy == AgentStrategy.PLAN_EXECUTE;
            }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest request) {
                return success("verified observation", List.of(tool(42, "src/Main.java", "h1")));
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier, new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult react = runtime.run(projectRequest(AgentStrategy.SINGLE_STEP_REACT));
        AgentRuntimeResult plan = runtime.run(projectRequest(AgentStrategy.PLAN_EXECUTE));

        assertThat(react.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(plan.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    @Test
    void regularChatWithoutEvidenceRemainsCompatible() {
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentStrategy.DIRECT, 1L, List.of(), 7L, "润色论文摘要",
                "test", "model", null, null, 2, true, "paper", null, null, null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of(), 0, 0, "chat"), null, null, "trace", null, null);

        AgentRuntimeResult result = verifier.verify(request, success("润色结果", List.of()));

        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy) {
        return projectRequest(strategy, List.of());
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy, List<ChatMessage> history) {
        return new AgentRuntimeRequest(strategy, 1L, history, 7L, "审查代码", "test", "model", null, null, 2,
                true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 2, 1, "project"), null, null, "trace", null, null)
                .withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private ChatMessage tool(long projectId, String path, String hash) {
        return new ChatMessage("tool", "{\"projectId\":" + projectId + ",\"relativePath\":\"" + path
                + "\",\"hash\":\"" + hash + "\",\"version\":\"" + hash + "\"}", null, "call-1");
    }

    private AgentRuntimeResult success(String content, List<ChatMessage> messages) {
        return new AgentRuntimeResult(true, content, messages, 1, null, List.of(), List.of(), null, null, null);
    }
}
