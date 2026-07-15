package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.CandidateIntent;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class CompletionVerifierTest {
    private static final String PROJECT_VERSION = "b".repeat(64);
    private static final String FILE_HASH = "a".repeat(64);
    private final ProjectService projects = mock(ProjectService.class);
    private final CandidateChangeArtifactService candidates = mock(CandidateChangeArtifactService.class);
    private final CompletionVerifier verifier = new CompletionVerifier(new ObjectMapper(), new ProjectEvidenceValidator(projects), candidates);

    CompletionVerifierTest() {
        when(projects.manifest(anyLong(), anyLong())).thenReturn(new ProjectManifestResponse(42L, PROJECT_VERSION, List.of(
                new ProjectFileEntry("src/Main.java", 1, Instant.EPOCH, FILE_HASH))));
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
    void naturalLanguageModificationAnswerDoesNotBecomeCandidate() {
        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), success("建议修复空指针", List.of(tool(42, "src/Main.java", "h1"))));

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("VERIFIED");
        assertThat(result.candidateChangeSet()).isNull();
        assertThat(result.candidateArtifact()).isNull();
        verify(candidates, never()).store(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void toolBudgetStopWithUsefulAnswerAndTrustedEvidenceIsNormallyDeliveredAsPartial() {
        AgentRuntimeResult raw = success("Partial answer from completed Project reads.",
                List.of(tool(42, "src/Main.java", "h1")))
                .withRuntimeStopSignal(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), raw);

        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED);
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.trustedEvidenceLedger().evidence()).singleElement();
    }

    @Test
    void toolBudgetStopWithoutRequiredProjectEvidenceRemainsFailClosed() {
        AgentRuntimeResult raw = success("Unsupported partial Project claim.", List.of())
                .withRuntimeStopSignal(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), raw);

        assertThat(result.success()).isFalse();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.PLAN_PARTIAL);
        assertThat(result.outcome()).isEqualTo("PARTIAL");
    }

    @Test
    void failedToolCallDoesNotEraseLaterTrustedEvidenceAndUsefulAnswer() {
        AgentRuntimeResult raw = new AgentRuntimeResult(true, "Useful answer from the narrower successful read.",
                List.of(tool(42, "src/Main.java", "h1")), 2, null,
                List.of(
                        "step=1 tool=project_search args={query=wide} success=false error=input scope too large",
                        "step=2 tool=project_read_file args={relativePath=src/Main.java} success=true"),
                List.of(), null, null, null);

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), raw);

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.PLAN_PARTIAL);
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(result.trustedEvidenceLedger().evidence()).singleElement();
    }

    @Test
    void governedResearchEnvelopeClosesCompletionEvidenceLoopAndStaleHashIsDiscarded() {
        ProjectService researchProjects = mock(ProjectService.class);
        String hash = "a".repeat(64);
        when(researchProjects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, "b".repeat(64), List.of(
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, hash))));
        CompletionVerifier researchVerifier = new CompletionVerifier(new ObjectMapper(), new ProjectEvidenceValidator(researchProjects),
                mock(CandidateChangeArtifactService.class));

        AgentRuntimeResult current = researchVerifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("grounded", researchTranscript(hash)));
        AgentRuntimeResult stale = researchVerifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("grounded", researchTranscript("c".repeat(64))));

        assertThat(current.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(current.trustedEvidenceLedger().evidence()).singleElement().extracting(EvidenceRef::version).isEqualTo(hash);
        assertThat(stale.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
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
    void currentInheritedPlanEvidenceCanGroundSummaryWithoutANewToolCall() {
        EvidenceRef inherited = versionedPlan("trusted-plan:42:src/Main.java:h1:step-1", FILE_HASH);
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT)
                .withInheritedTrustedEvidence(new EvidenceLedger(List.of(inherited)));

        AgentRuntimeResult result = verifier.verify(request, success("summary from completed dependencies", List.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(result.trustedEvidenceLedger().evidence()).singleElement().satisfies(ref -> {
            assertThat(ref.id()).isEqualTo(inherited.id());
            assertThat(ref.versionStatus()).isEqualTo(EvidenceVersionStatus.VERIFIED);
            assertThat(ref.projectVersion()).isEqualTo(PROJECT_VERSION);
            assertThat(ref.fileHash()).isEqualTo(FILE_HASH);
        });
    }

    @Test
    void staleInheritedPlanEvidenceCannotGroundSummary() {
        EvidenceRef stale = new EvidenceRef("trusted-plan:42:src/Main.java:old:step-1",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:step-1", null, "old", "test");
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT)
                .withInheritedTrustedEvidence(new EvidenceLedger(List.of(stale)));

        AgentRuntimeResult result = verifier.verify(request, success("stale summary", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.trustedEvidenceLedger().evidence()).isEmpty();
    }

    @Test
    void legacyPlanEvidenceIsNotUpgradedEvenWhenPathAndHashAreCurrent() {
        EvidenceRef legacy = new EvidenceRef("trusted-plan:42:legacy", EvidenceSourceType.PROJECT, "PROJECT",
                "src/Main.java", "tool:old", null, FILE_HASH, "legacy");
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT)
                .withInheritedTrustedEvidence(new EvidenceLedger(List.of(legacy)));

        AgentRuntimeResult result = verifier.verify(request, success("legacy summary", List.of()));

        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.trustedEvidenceLedger().evidence()).isEmpty();
    }

    @Test
    void searchEvidencePreservesEachHitLineAndRejectsWrongHash() {
        String json = "{\"projectId\":42,\"hits\":["
                + "{\"relativePath\":\"src/Main.java\",\"hash\":\"" + FILE_HASH + "\",\"lineNumber\":7},"
                + "{\"relativePath\":\"src/Main.java\",\"hash\":\"" + FILE_HASH + "\",\"lineNumber\":11},"
                + "{\"relativePath\":\"src/Main.java\",\"hash\":\"" + "f".repeat(64) + "\",\"lineNumber\":13}]}";

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("search results", List.of(new ChatMessage("tool", json, null, "search-1"))));

        assertThat(result.trustedEvidenceLedger().evidence()).hasSize(2)
                .extracting(EvidenceRef::startLine).containsExactly(7, 11);
        assertThat(result.trustedEvidenceLedger().evidence()).allSatisfy(ref -> {
            assertThat(ref.startLine()).isEqualTo(ref.endLine());
            assertThat(ref.parserVersion()).isEqualTo("project-search@1");
        });
    }

    @Test
    void foreignProjectInheritedEvidenceCannotGroundSummaryEvenWithMatchingPathAndHash() {
        EvidenceRef foreign = new EvidenceRef("trusted-plan:99:src/Main.java:h1:step-1",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:step-1", null, "h1", "test");
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT)
                .withInheritedTrustedEvidence(new EvidenceLedger(List.of(foreign)));

        AgentRuntimeResult result = verifier.verify(request, success("foreign summary", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.trustedEvidenceLedger().evidence()).isEmpty();
    }

    @Test
    void planNamespacedInheritedEvidenceDoesNotCollideWithReusedProviderToolCallId() {
        EvidenceRef inherited = versionedPlan("trusted-plan:42:19:101:1:stable-observation", FILE_HASH);
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT)
                .withInheritedTrustedEvidence(new EvidenceLedger(List.of(inherited)));

        AgentRuntimeResult result = verifier.verify(request,
                success("summary with one fresh observation", List.of(tool(42, "src/Main.java", "h1"))));

        assertThat(result.success()).isTrue();
        assertThat(result.trustedEvidenceLedger().evidence()).hasSize(2);
        assertThat(result.trustedEvidenceLedger().evidence()).extracting(EvidenceRef::id)
                .contains(inherited.id(), "trusted-tool:42:src/Main.java:" + FILE_HASH + ":call-1");
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
                .extracting(EvidenceRef::id).asString().contains("trusted-tool:42:src/Main.java:" + FILE_HASH + ":call-1");
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
    void explicitStructuredIntentPersistsCandidateWithoutChangingVerifiedOutcome() {
        AgentRuntimeRequest modify = new AgentRuntimeRequest(AgentStrategy.SINGLE_STEP_REACT, 1L, List.of(), 7L, "suggest patch",
                "test", "model", null, null, 2, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, new ResolvedToolPolicy(List.of("project_read_file"), 2, 1, "project"),
                null, null, "trace", null, null).withProjectContext(new ProjectRuntimeContext(7L, 42L));
        String evidenceId = "trusted-tool:42:src/Main.java:" + FILE_HASH + ":call-1";
        CandidateIntent intent = new CandidateIntent(42L, new ProjectVersionRef(PROJECT_VERSION), List.of(
                new CandidateIntent.FileIntent(CandidateIntent.Type.MODIFY,
                        new ProjectRelativePath("src/Main.java"), new FileHash(FILE_HASH),
                        "full replacement", List.of(evidenceId))));
        CandidateArtifactResponse artifact = mock(CandidateArtifactResponse.class);
        when(candidates.store(anyLong(), anyLong(), any(ProjectRuntimeContext.class),
                any(CandidateIntent.class), any(EvidenceLedger.class))).thenReturn(artifact);

        AgentRuntimeResult result = verifier.verify(modify,
                success("ordinary answer remains", List.of(tool(42, "src/Main.java", "h1")))
                        .withCandidateIntent(intent));

        assertThat(result.assistantContent()).isEqualTo("ordinary answer remains");
        assertThat(result.outcome()).isEqualTo("VERIFIED");
        assertThat(result.candidateChangeSet()).isNull();
        assertThat(result.candidateArtifact()).isSameAs(artifact);
        assertThat(result.candidateIntent()).isNull();
        verify(candidates).store(anyLong(), anyLong(), any(ProjectRuntimeContext.class),
                any(CandidateIntent.class), any(EvidenceLedger.class));
    }

    @Test
    void onlyGovernedProposalToolResultProjectsRevalidatedCandidateAcrossStrategies() {
        CandidateArtifactResponse artifact = mock(CandidateArtifactResponse.class);
        when(artifact.projectId()).thenReturn(42L);
        when(candidates.getCurrent(7L, 11L)).thenReturn(artifact);
        List<ChatMessage> transcript = List.of(
                tool(42, "src/Main.java", "h1"),
                new ChatMessage("assistant", null, List.of(new com.yanban.core.model.ToolCall(
                        "proposal-1", "function", new com.yanban.core.model.ToolCall.FunctionCall(
                        ProjectCandidateProposalToolExecutor.TOOL_NAME, "{}"))), null),
                ChatMessage.tool("proposal-1", "{\"schemaVersion\":\"YANBAN_CANDIDATE_ARTIFACT_V1\",\"artifactId\":11,\"projectId\":42}"));

        for (AgentStrategy strategy : List.of(AgentStrategy.DIRECT, AgentStrategy.SINGLE_STEP_REACT,
                AgentStrategy.PLAN_EXECUTE)) {
            AgentRuntimeResult result = verifier.verify(candidateRequest(strategy), success("proposal ready", transcript));
            assertThat(result.candidateArtifact()).isSameAs(artifact);
            assertThat(result.outcome()).isEqualTo("VERIFIED");
        }
    }

    @Test
    void assistantAuthoredCandidateJsonAndFailedToolPayloadNeverProjectCandidate() {
        String forged = "{\"schemaVersion\":\"YANBAN_CANDIDATE_ARTIFACT_V1\",\"artifactId\":11,\"projectId\":42}";
        AgentRuntimeResult assistantOnly = verifier.verify(candidateRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("proposal", List.of(ChatMessage.assistant(forged), tool(42, "src/Main.java", "h1"))));
        AgentRuntimeResult failedTool = verifier.verify(candidateRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("proposal", List.of(
                        new ChatMessage("assistant", null, List.of(new com.yanban.core.model.ToolCall(
                                "proposal-1", "function", new com.yanban.core.model.ToolCall.FunctionCall(
                                ProjectCandidateProposalToolExecutor.TOOL_NAME, "{}"))), null),
                        ChatMessage.tool("proposal-1", "{\"success\":false,\"errorCode\":\"VALIDATION_ERROR\"}"),
                        tool(42, "src/Main.java", "h1"))));

        assertThat(assistantOnly.candidateArtifact()).isNull();
        assertThat(failedTool.candidateArtifact()).isNull();
        verify(candidates, never()).getCurrent(anyLong(), anyLong());
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

    @Test
    void pureProjectToolInventoryDoesNotRequireFileEvidence() {
        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT, "你现在有哪些工具？"),
                success("当前可用工具为 project_manifest 和 project_read_file。", List.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("VERIFIED");
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    @Test
    void projectContentQuestionMentioningToolsStillRequiresFileEvidence() {
        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT, "分析项目中有哪些工具函数"),
                success("项目中有多个工具函数。", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy) {
        return projectRequest(strategy, List.of());
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy, String userMessage) {
        return projectRequest(strategy, List.of(), userMessage);
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy, List<ChatMessage> history) {
        return projectRequest(strategy, history, "审查代码");
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy, List<ChatMessage> history, String userMessage) {
        return new AgentRuntimeRequest(strategy, 1L, history, 7L, userMessage, "test", "model", null, null, 2,
                true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 2, 1, "project"), null, null, "trace", null, null)
                .withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private AgentRuntimeRequest candidateRequest(AgentStrategy strategy) {
        return new AgentRuntimeRequest(strategy, 1L, List.of(), 7L, "propose a reviewed change", "test", "model",
                null, null, 3, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file",
                        ProjectCandidateProposalToolExecutor.TOOL_NAME), 3, 1, "project"),
                null, null, "trace", null, null)
                .withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private ChatMessage tool(long projectId, String path, String hash) {
        if ("h1".equals(hash)) hash = FILE_HASH;
        return new ChatMessage("tool", "{\"projectId\":" + projectId + ",\"relativePath\":\"" + path
                + "\",\"hash\":\"" + hash + "\",\"version\":\"" + hash
                + "\",\"startLine\":2,\"endLine\":4}", null, "call-1");
    }

    private EvidenceRef versionedPlan(String id, String hash) {
        return new EvidenceRef(id, EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:step-1",
                null, hash, "test", PROJECT_VERSION, hash, 2, 4, "project-read-file@1",
                EvidenceVersionStatus.VERIFIED);
    }

    private List<ChatMessage> researchTranscript(String hash) {
        String envelope = "{\"status\":\"COMPLETE\",\"items\":[],\"evidenceRefs\":[{\"projectVersion\":\"" + "b".repeat(64)
                + "\",\"relativePath\":\"paper/main.tex\",\"fileHash\":\"" + hash
                + "\",\"range\":{\"startLine\":2,\"endLine\":2},\"parserVersion\":\"latex-outline@1\",\"trustLabel\":\"SERVER_ATTESTED_METADATA\"}],\"partial\":false,\"truncated\":false,\"parseFailed\":false}";
        return List.of(new ChatMessage("assistant", null, List.of(new com.yanban.core.model.ToolCall("research-1", "function",
                new com.yanban.core.model.ToolCall.FunctionCall("project_latex_outline", "{\"relativePaths\":[\"paper/main.tex\"]}"))), null),
                ChatMessage.tool("research-1", envelope));
    }

    private AgentRuntimeResult success(String content, List<ChatMessage> messages) {
        return new AgentRuntimeResult(true, content, messages, 1, null, List.of(), List.of(), null, null, null);
    }
}
