package com.yanban.core.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ParserVersionRef;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchEvidenceRef;
import com.yanban.core.research.ResearchRuntimeScope;
import com.yanban.core.research.ResearchToolContracts;
import com.yanban.core.research.SourceRange;
import com.yanban.core.research.TrustLabel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkerContractsTest {
    private static final long USER_ID = 7;
    private static final long PROJECT_ID = 42;
    private static final ProjectVersionRef VERSION = new ProjectVersionRef("manifest-sha256:worker12a");
    private static final ProjectVersionRef OTHER_VERSION = new ProjectVersionRef("manifest-sha256:other");
    private static final FileHash HASH_A = new FileHash("a".repeat(64));
    private static final FileHash HASH_B = new FileHash("b".repeat(64));
    private static final ProjectRelativePath PAPER = path("paper/main.tex");
    private static final ProjectRelativePath CONFIG = path("config/train.yaml");
    private static final ProjectRelativePath CODE = path("src/Main.java");
    private static final List<String> ALL_TOOLS = List.of(
            ResearchToolContracts.PROJECT_LATEX_OUTLINE,
            ResearchToolContracts.PROJECT_BIBTEX_AUDIT,
            ResearchToolContracts.PROJECT_CODE_SYMBOLS,
            ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY,
            ResearchToolContracts.PROJECT_CROSS_MATERIAL_SEARCH);
    private static final List<String> ALL_FINDING_KEYS = List.of(
            "finding-z", "finding-a", "finding", "outside", "learning-rate", "single-claim",
            "first", "second", "report", "material-type", "receipt-evidence", "receipt-tool");
    private static final WorkerBudget GENEROUS_BUDGET = new WorkerBudget(20, 20, 40, 80,
            1_000_000, 200_000);

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper lenientJson = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void packetIsImmutableDeterministicAndRoundTripsWithoutAuthority() throws Exception {
        ResearchEvidenceRef paper = evidence(VERSION, PAPER, 2, HASH_A);
        ResearchEvidenceRef config = evidence(VERSION, CONFIG, 1, HASH_B);
        WorkerTaskPacket first = packet("worker-2", VERSION,
                List.of(PAPER, CONFIG, PAPER),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                        ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY,
                        ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                GENEROUS_BUDGET, List.of(paper, config, paper),
                List.of("Check reported settings", "Trace the paper claim", "Check reported settings"));
        WorkerTaskPacket reordered = packet("worker-2", VERSION, List.of(CONFIG, PAPER),
                List.of(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY,
                        ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                GENEROUS_BUDGET, List.of(config, paper),
                List.of("Trace the paper claim", "Check reported settings"));

        assertThat(first).isEqualTo(reordered);
        assertThat(first.materialScope()).containsExactly(CONFIG, PAPER);
        assertThat(first.materialAssignments()).containsExactly(
                new WorkerMaterialAssignment(CONFIG, WorkerMaterialType.CONFIGURATION),
                new WorkerMaterialAssignment(PAPER, WorkerMaterialType.PAPER));
        assertThat(first.allowedReadTools()).containsExactly(
                ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY,
                ResearchToolContracts.PROJECT_LATEX_OUTLINE);
        assertThat(first.evidenceRefs()).containsExactly(config, paper);
        assertThatThrownBy(() -> first.materialScope().add(CODE))
                .isInstanceOf(UnsupportedOperationException.class);

        String serialized = json.writeValueAsString(first);
        assertThat(json.readValue(serialized, WorkerTaskPacket.class)).isEqualTo(first);
        assertThat(serialized).contains("materialAssignments", "allowedFindingKeys")
                .doesNotContain("materialScope");
        assertThat(serialized).doesNotContain("userId", "projectId", "trustedCapabilities",
                "authority", "chainOfThought", "absolutePath", "secret");
    }

    @Test
    void packetSupportsEmptyAndSingleMaterialAssignments() {
        WorkerTaskPacket empty = packet("worker-empty", VERSION, List.of(), List.of(),
                new WorkerBudget(1, 1, 1, 1, 1, 1), List.of(), List.of("Return no findings"));
        WorkerTaskPacket single = packet("worker-single", VERSION, List.of(PAPER),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), GENEROUS_BUDGET,
                List.of(evidence(VERSION, PAPER, 1, HASH_A)), List.of("Inspect one paper"));

        assertThat(empty.materialScope()).isEmpty();
        assertThat(empty.allowedReadTools()).isEmpty();
        assertThat(empty.evidenceRefs()).isEmpty();
        assertThat(single.materialScope()).containsExactly(PAPER);
    }

    @Test
    void packetRejectsUnsafePathsSensitiveTextAndReasoningTraces() {
        for (String unsafe : List.of("../paper.tex", "/paper.tex", "C:/paper.tex",
                "C:\\paper.tex", "\\\\server\\share\\paper.tex", "paper\\main.tex")) {
            assertThatThrownBy(() -> path(unsafe)).as(unsafe).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> packetWithObjective("Inspect C:/private/paper.tex"))
                .hasMessageContaining("absolute path");
        assertThatThrownBy(() -> packetWithObjective("Use api_key=abcd1234"))
                .hasMessageContaining("credential");
        assertThatThrownBy(() -> packetWithObjective("chain-of-thought: hidden steps"))
                .hasMessageContaining("reasoning trace");
        assertThatThrownBy(() -> packetWithObjective("-----BEGIN PRIVATE KEY-----"))
                .hasMessageContaining("credential");
        for (String hostPath : List.of("/mnt/data/paper.tex", "/workspace/project/paper.tex",
                "/usr/local/share/paper.tex")) {
            assertThatThrownBy(() -> packetWithObjective("Inspect " + hostPath))
                    .as(hostPath).hasMessageContaining("absolute path");
        }
        assertThat(packetWithObjective("Compare https://example.test/a/b and LaTeX a/b").objective())
                .contains("https://example.test/a/b", "LaTeX a/b");
    }

    @Test
    void packetEvidenceMustUseAssignedVersionAndScopeAndHardLimitsApply() {
        assertThatThrownBy(() -> packet("worker-cross-version", VERSION, List.of(PAPER), ALL_TOOLS,
                GENEROUS_BUDGET, List.of(evidence(OTHER_VERSION, PAPER, 1, HASH_A)),
                List.of("Check version"))).hasMessageContaining("Project version");
        assertThatThrownBy(() -> packet("worker-outside", VERSION, List.of(PAPER), ALL_TOOLS,
                GENEROUS_BUDGET, List.of(evidence(VERSION, CONFIG, 1, HASH_A)),
                List.of("Check scope"))).hasMessageContaining("outside");
        assertThatThrownBy(() -> new WorkerBudget(WorkerBudget.HARD_MAX_INPUT_PATHS + 1,
                1, 1, 1, 1, 1)).hasMessageContaining("hard limit");
        assertThatThrownBy(() -> packet("worker-budget", VERSION, List.of(PAPER, CONFIG), ALL_TOOLS,
                new WorkerBudget(1, 2, 2, 2, 100, 100), List.of(), List.of("Check budget")))
                .hasMessageContaining("assignment exceeds");

        List<String> tooManyFindingKeys = new ArrayList<>();
        for (int index = 0; index <= WorkerTaskPacket.MAX_ALLOWED_FINDING_KEYS; index++) {
            tooManyFindingKeys.add("finding-key-" + index);
        }
        assertThatThrownBy(() -> new WorkerTaskPacket("worker-many-keys", "turn:run-1", VERSION,
                List.of(), "Compare assigned research materials", List.of("Return no findings"),
                List.of(), tooManyFindingKeys, GENEROUS_BUDGET, List.of()))
                .hasMessageContaining("finding keys exceed");

        List<ProjectRelativePath> tooManyPaths = new ArrayList<>();
        for (int index = 0; index <= WorkerTaskPacket.MAX_MATERIAL_PATHS; index++) {
            tooManyPaths.add(path("data/file-" + index + ".txt"));
        }
        assertThatThrownBy(() -> packet("worker-many", VERSION, tooManyPaths, List.of(),
                GENEROUS_BUDGET, List.of(), List.of("Check limit"))).hasMessageContaining("limit");
    }

    @Test
    void packetJsonRejectsUnknownLegacyAuthorityAndTamperingEvenWithLenientMapper() throws Exception {
        WorkerTaskPacket packet = packet("worker-json", VERSION, List.of(PAPER),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), GENEROUS_BUDGET,
                List.of(evidence(VERSION, PAPER, 1, HASH_A)), List.of("Inspect paper"));
        ObjectNode base = (ObjectNode) json.readTree(json.writeValueAsString(packet));

        for (String field : List.of("userId", "authority", "trustedCapabilities", "chainOfThought",
                "completionAuthority", "writeAuthority", "applyAuthority", "candidate")) {
            ObjectNode malicious = base.deepCopy();
            malicious.put(field, true);
            assertThatThrownBy(() -> lenientJson.treeToValue(malicious, WorkerTaskPacket.class))
                    .as(field).isInstanceOf(JsonProcessingException.class)
                    .hasMessageContaining("unknown worker contract field");
        }
        for (String required : List.of("workerTaskId", "parentRunId", "projectVersion",
                "materialAssignments", "objective", "successCriteria", "allowedReadTools",
                "allowedFindingKeys", "budget", "evidenceRefs", "fingerprint")) {
            ObjectNode legacy = base.deepCopy();
            legacy.remove(required);
            assertThatThrownBy(() -> json.treeToValue(legacy, WorkerTaskPacket.class))
                    .as(required).isInstanceOf(JsonProcessingException.class);
        }
        ObjectNode tampered = base.deepCopy();
        tampered.put("objective", "Different objective");
        assertThatThrownBy(() -> json.treeToValue(tampered, WorkerTaskPacket.class))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("fingerprint");

        ObjectNode maliciousType = base.deepCopy();
        ((ObjectNode) maliciousType.path("materialAssignments").get(0)).put("materialType", "CODE");
        assertThatThrownBy(() -> lenientJson.treeToValue(maliciousType, WorkerTaskPacket.class))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("fingerprint");
        ObjectNode unknownType = base.deepCopy();
        ((ObjectNode) unknownType.path("materialAssignments").get(0)).put("materialType", "COMMAND");
        assertThatThrownBy(() -> lenientJson.treeToValue(unknownType, WorkerTaskPacket.class))
                .isInstanceOf(JsonProcessingException.class);
        ObjectNode maliciousKey = base.deepCopy();
        maliciousKey.withArray("allowedFindingKeys").add("worker-controlled-key");
        assertThatThrownBy(() -> lenientJson.treeToValue(maliciousKey, WorkerTaskPacket.class))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("fingerprint");
        ObjectNode assignmentUnknown = base.deepCopy();
        ((ObjectNode) assignmentUnknown.path("materialAssignments").get(0)).put("authority", true);
        assertThatThrownBy(() -> lenientJson.treeToValue(assignmentUnknown, WorkerTaskPacket.class))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("unknown worker contract field");

        ObjectNode nestedBudget = base.deepCopy();
        ((ObjectNode) nestedBudget.path("budget")).put("unbounded", true);
        assertThatThrownBy(() -> lenientJson.treeToValue(nestedBudget, WorkerTaskPacket.class))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("unknown worker contract field");

        ObjectNode nestedEvidence = base.deepCopy();
        ((ObjectNode) nestedEvidence.path("evidenceRefs").get(0)).put("completionAuthority", true);
        assertThatThrownBy(() -> lenientJson.treeToValue(nestedEvidence, WorkerTaskPacket.class))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("exactly its declared fields");
    }

    @Test
    void serverAuthorityValidatesTrustedIdentityScopeCapabilityAllowlistAndBudget() {
        WorkerServerAuthority authority = authority(USER_ID, PROJECT_ID, VERSION, "run-1", ALL_TOOLS,
                GENEROUS_BUDGET);
        WorkerTaskPacket valid = packet("worker-authorized", VERSION, List.of(PAPER),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), GENEROUS_BUDGET,
                List.of(evidence(VERSION, PAPER, 1, HASH_A)), List.of("Inspect paper"));
        assertThat(WorkerTaskAttestor.attestServerResolved(authority, valid).packet()).isEqualTo(valid);

        assertThatThrownBy(() -> WorkerServerAuthority.serverResolved(identity(USER_ID, PROJECT_ID, "run-1"),
                new ResearchRuntimeScope(PROJECT_ID, USER_ID + 1,
                        Set.of(WorkerServerAuthority.REQUIRED_READ_CAPABILITY), VERSION),
                ALL_TOOLS, GENEROUS_BUDGET)).hasMessageContaining("trusted user");
        assertThatThrownBy(() -> WorkerServerAuthority.serverResolved(identity(USER_ID, PROJECT_ID, "run-1"),
                new ResearchRuntimeScope(PROJECT_ID + 1, USER_ID,
                        Set.of(WorkerServerAuthority.REQUIRED_READ_CAPABILITY), VERSION),
                ALL_TOOLS, GENEROUS_BUDGET)).hasMessageContaining("trusted Project");
        assertThatThrownBy(() -> WorkerServerAuthority.serverResolved(identity(USER_ID, PROJECT_ID, "run-1"),
                new ResearchRuntimeScope(PROJECT_ID, USER_ID, Set.of(), VERSION),
                ALL_TOOLS, GENEROUS_BUDGET)).hasMessageContaining("capability is not present");

        WorkerServerAuthority narrowTools = authority(USER_ID, PROJECT_ID, VERSION, "run-1",
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), GENEROUS_BUDGET);
        WorkerTaskPacket toolOverreach = packet("worker-tool-overreach", VERSION, List.of(PAPER),
                List.of(ResearchToolContracts.PROJECT_CODE_SYMBOLS), GENEROUS_BUDGET,
                List.of(), List.of("Inspect symbols"));
        assertThatThrownBy(() -> WorkerTaskAttestor.attestServerResolved(narrowTools, toolOverreach))
                .hasMessageContaining("allowlist");

        WorkerServerAuthority smallBudget = authority(USER_ID, PROJECT_ID, VERSION, "run-1", ALL_TOOLS,
                new WorkerBudget(1, 1, 1, 1, 100, 100));
        assertThatThrownBy(() -> WorkerTaskAttestor.attestServerResolved(smallBudget, valid))
                .hasMessageContaining("parent budget");
        assertThatThrownBy(() -> WorkerTaskAttestor.attestServerResolved(authority,
                packetForParent("worker-parent", "turn:other", VERSION)))
                .hasMessageContaining("different parent");
        assertThatThrownBy(() -> WorkerTaskAttestor.attestServerResolved(authority,
                packet("worker-version", OTHER_VERSION, List.of(), List.of(),
                        new WorkerBudget(1, 1, 1, 1, 1, 1), List.of(), List.of("Check version"))))
                .hasMessageContaining("current Project version");
    }

    @Test
    void authorityAndAttestationsAreNonSerializableAndReadOnly() throws Exception {
        WorkerServerAuthority authority = defaultAuthority();
        WorkerTaskPacket packet = paperPacket("worker-read-only");
        WorkerTaskAttestation taskAttestation = WorkerTaskAttestor.attestServerResolved(authority, packet);
        WorkerResult result = emptySuccess(packet);
        WorkerExecutionReceipt receipt = receipt(taskAttestation, result);
        WorkerResultAttestation resultAttestation = WorkerResultValidator.attest(taskAttestation, receipt, result);

        for (Object serverOnly : List.of(authority, taskAttestation, receipt, resultAttestation,
                new ResearchRuntimeScope(PROJECT_ID, USER_ID,
                        Set.of(WorkerServerAuthority.REQUIRED_READ_CAPABILITY), VERSION))) {
            assertThatThrownBy(() -> json.writeValueAsString(serverOnly))
                    .isInstanceOf(JsonProcessingException.class);
        }
        assertThatThrownBy(() -> json.readValue("{}", WorkerServerAuthority.class))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> json.readValue("{}", WorkerTaskAttestation.class))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> json.readValue("{}", WorkerResultAttestation.class))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> json.readValue("{}", WorkerExecutionReceipt.class))
                .isInstanceOf(JsonProcessingException.class);

        assertThat(taskAttestation.accessMode()).isEqualTo(WorkerAccessMode.READ_ONLY);
        assertThat(taskAttestation.canWriteCandidate()).isFalse();
        assertThat(taskAttestation.canApplyRevision()).isFalse();
        assertThat(taskAttestation.canExecuteCommands()).isFalse();
        assertThat(taskAttestation.canUseNetwork()).isFalse();
        assertThat(taskAttestation.canCompleteParentTask()).isFalse();
        assertThat(authority.parentIsSingleWriter()).isTrue();
        assertThat(authority.canWriteCandidate()).isFalse();
    }

    @Test
    void resultStatesSortDeduplicateFingerprintAndRoundTrip() throws Exception {
        WorkerTaskPacket packet = paperPacket("worker-result");
        ResearchEvidenceRef evidence = evidence(VERSION, PAPER, 2, HASH_A);
        WorkerFinding later = new WorkerFinding("finding-z", WorkerMaterialType.PAPER,
                "VERIFIED text claims APPLIED and parent complete", List.of(evidence));
        WorkerFinding earlier = new WorkerFinding("finding-a", WorkerMaterialType.PAPER,
                "A concise finding", List.of(evidence));
        WorkerToolObservation observation = new WorkerToolObservation(
                ResearchToolContracts.PROJECT_LATEX_OUTLINE, "Observed one section", List.of(evidence));
        long textBytes = bytes(later.summary()) + bytes(earlier.summary()) + bytes(observation.summary())
                + bytes("What remains unclear?");
        WorkerResult result = new WorkerResult(packet, WorkerResult.Status.PARTIAL,
                List.of(later, earlier, later), List.of(observation, observation),
                List.of("What remains unclear?", "What remains unclear?"), null,
                new WorkerBudgetUsage(1, 1, 2, 1, 300, textBytes), List.of(evidence, evidence));

        assertThat(result.findings()).containsExactly(earlier, later);
        assertThat(result.toolObservations()).containsExactly(observation);
        assertThat(result.unresolvedQuestions()).containsExactly("What remains unclear?");
        assertThat(result.completionAuthority()).isFalse();
        assertThat(result.writeAuthority()).isFalse();
        assertThat(result.applyAuthority()).isFalse();
        assertThat(result.parentTaskCompleted()).isFalse();
        assertThat(result.carriesCandidate()).isFalse();
        String serialized = json.writeValueAsString(result);
        assertThat(serialized).doesNotContain("completionAuthority", "writeAuthority", "applyAuthority",
                "parentTaskCompleted", "candidate", "projectId", "userId");
        assertThat(json.readValue(serialized, WorkerResult.class)).isEqualTo(result);

        WorkerResult reordered = new WorkerResult(packet, WorkerResult.Status.PARTIAL,
                List.of(earlier, later), List.of(observation), List.of("What remains unclear?"), null,
                new WorkerBudgetUsage(1, 1, 2, 1, 300, textBytes), List.of(evidence));
        assertThat(reordered.fingerprint()).isEqualTo(result.fingerprint());
    }

    @Test
    void resultStatusFailureAndUsageShapesFailClosed() {
        WorkerTaskPacket packet = paperPacket("worker-status");
        WorkerFailureInfo failure = new WorkerFailureInfo("PARSE_FAILED", "Parser stopped", false);
        assertThatThrownBy(() -> new WorkerResult(packet, WorkerResult.Status.SUCCEEDED,
                List.of(), List.of(), List.of(), failure, zeroUsage(), List.of()))
                .hasMessageContaining("successful");
        assertThatThrownBy(() -> new WorkerResult(packet, WorkerResult.Status.FAILED,
                List.of(), List.of(), List.of(), null, zeroUsage(), List.of()))
                .hasMessageContaining("require failure");
        assertThatThrownBy(() -> new WorkerResult(packet, WorkerResult.Status.CANCELLED,
                List.of(), List.of(), List.of(), null, zeroUsage(), List.of()))
                .hasMessageContaining("require failure");
        assertThatThrownBy(() -> new WorkerResult(packet, WorkerResult.Status.SUCCEEDED,
                List.of(), List.of(), List.of("Blocking question"), null,
                new WorkerBudgetUsage(0, 0, 0, 0, 0, bytes("Blocking question")), List.of()))
                .hasMessageContaining("must be PARTIAL");

        WorkerResult failed = new WorkerResult(packet, WorkerResult.Status.FAILED,
                List.of(), List.of(), List.of(), failure,
                new WorkerBudgetUsage(0, 0, 0, 0, 0, bytes(failure.message())), List.of());
        WorkerResult partial = new WorkerResult(packet, WorkerResult.Status.PARTIAL,
                List.of(), List.of(), List.of(), failure,
                new WorkerBudgetUsage(0, 0, 0, 0, 0, bytes(failure.message())), List.of());
        assertThat(failed.status()).isEqualTo(WorkerResult.Status.FAILED);
        assertThat(partial.status()).isEqualTo(WorkerResult.Status.PARTIAL);

        ResearchEvidenceRef evidence = evidence(VERSION, PAPER, 1, HASH_A);
        WorkerFinding finding = new WorkerFinding("finding", WorkerMaterialType.PAPER, "Finding", List.of(evidence));
        assertThatThrownBy(() -> new WorkerResult(packet, WorkerResult.Status.SUCCEEDED,
                List.of(finding), List.of(), List.of(), null,
                new WorkerBudgetUsage(1, 0, 0, 1, 1, bytes(finding.summary())), List.of(evidence)))
                .hasMessageContaining("usage does not match");
        assertThatThrownBy(() -> new WorkerResult(packet, WorkerResult.Status.SUCCEEDED,
                List.of(finding), List.of(), List.of(), null,
                new WorkerBudgetUsage(1, 0, 1, 1, 1, bytes(finding.summary()) + 1), List.of(evidence)))
                .hasMessageContaining("usage does not match");
    }

    @Test
    void resultRejectsEvidenceToolBudgetAndTaskBoundaryOverreach() {
        WorkerTaskPacket paperPacket = paperPacket("worker-boundary");
        ResearchEvidenceRef outside = evidence(VERSION, CONFIG, 1, HASH_A);
        WorkerFinding outsideFinding = new WorkerFinding("outside", WorkerMaterialType.CONFIGURATION,
                "Outside finding", List.of(outside));
        assertThatThrownBy(() -> new WorkerResult(paperPacket, WorkerResult.Status.SUCCEEDED,
                List.of(outsideFinding), List.of(), List.of(), null,
                new WorkerBudgetUsage(1, 0, 1, 1, 1, bytes(outsideFinding.summary())), List.of(outside)))
                .hasMessageContaining("outside");

        WorkerToolObservation wrongTool = new WorkerToolObservation(
                ResearchToolContracts.PROJECT_CODE_SYMBOLS, "Observed code", List.of());
        assertThatThrownBy(() -> new WorkerResult(paperPacket, WorkerResult.Status.SUCCEEDED,
                List.of(), List.of(wrongTool), List.of(), null,
                new WorkerBudgetUsage(0, 1, 0, 0, 0, bytes(wrongTool.summary())), List.of()))
                .hasMessageContaining("outside the task allowlist");

        assertThatThrownBy(() -> new WorkerResult(paperPacket, WorkerResult.Status.SUCCEEDED,
                List.of(), List.of(), List.of(), null,
                new WorkerBudgetUsage(2, 0, 0, 0, 0, 0), List.of()))
                .hasMessageContaining("more paths");

        WorkerTaskPacket otherTask = paperPacket("worker-other-task");
        WorkerResult otherResult = emptySuccess(otherTask);
        WorkerTaskAttestation attestation = WorkerTaskAttestor.attestServerResolved(defaultAuthority(), paperPacket);
        WorkerTaskAttestation otherAttestation = WorkerTaskAttestor.attestServerResolved(
                defaultAuthority(), otherTask);
        assertThatThrownBy(() -> WorkerResultValidator.attest(attestation,
                receipt(otherAttestation, otherResult), otherResult))
                .hasMessageContaining("different worker task");
    }

    @Test
    void materialAssignmentsAndFindingKeysAreServerBound() {
        WorkerTaskPacket packet = new WorkerTaskPacket("worker-material-binding", "turn:run-1", VERSION,
                List.of(new WorkerMaterialAssignment(PAPER, WorkerMaterialType.PAPER)),
                "Compare assigned research materials", List.of("Report one claim"),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), List.of("finding-a"),
                GENEROUS_BUDGET, List.of());
        ResearchEvidenceRef evidence = evidence(VERSION, PAPER, 1, HASH_A);

        WorkerFinding wrongKey = new WorkerFinding("finding-z", WorkerMaterialType.PAPER,
                "Wrong key", List.of(evidence));
        assertThatThrownBy(() -> new WorkerResult(packet, WorkerResult.Status.SUCCEEDED,
                List.of(wrongKey), List.of(), List.of(), null,
                new WorkerBudgetUsage(1, 1, 1, 1, 10, bytes(wrongKey.summary())), List.of(evidence)))
                .hasMessageContaining("key is outside");

        WorkerFinding wrongType = new WorkerFinding("finding-a", WorkerMaterialType.CODE,
                "Wrong material type", List.of(evidence));
        assertThatThrownBy(() -> new WorkerResult(packet, WorkerResult.Status.SUCCEEDED,
                List.of(wrongType), List.of(), List.of(), null,
                new WorkerBudgetUsage(1, 1, 1, 1, 10, bytes(wrongType.summary())), List.of(evidence)))
                .hasMessageContaining("material type");
        assertThatThrownBy(() -> new WorkerFinding("finding-a", WorkerMaterialType.PAPER,
                "No evidence", List.of())).hasMessageContaining("evidence-bound");

        assertThatThrownBy(() -> new WorkerTaskPacket("worker-ambiguous-material", "turn:run-1", VERSION,
                List.of(new WorkerMaterialAssignment(PAPER, WorkerMaterialType.PAPER),
                        new WorkerMaterialAssignment(PAPER, WorkerMaterialType.CODE)),
                "Compare assigned research materials", List.of("Report one claim"), List.of(),
                List.of("finding-a"), GENEROUS_BUDGET, List.of()))
                .hasMessageContaining("multiple assigned types");

        WorkerTaskPacket keyOrderOne = new WorkerTaskPacket("worker-key-order", "turn:run-1", VERSION,
                List.of(new WorkerMaterialAssignment(PAPER, WorkerMaterialType.PAPER)),
                "Compare assigned research materials", List.of("Report one claim"), List.of(),
                List.of("finding-z", "finding-a", "finding-z"), GENEROUS_BUDGET, List.of());
        WorkerTaskPacket keyOrderTwo = new WorkerTaskPacket("worker-key-order", "turn:run-1", VERSION,
                List.of(new WorkerMaterialAssignment(PAPER, WorkerMaterialType.PAPER)),
                "Compare assigned research materials", List.of("Report one claim"), List.of(),
                List.of("finding-a", "finding-z"), GENEROUS_BUDGET, List.of());
        assertThat(keyOrderOne.allowedFindingKeys()).containsExactly("finding-a", "finding-z");
        assertThat(keyOrderOne.fingerprint()).isEqualTo(keyOrderTwo.fingerprint());
    }

    @Test
    void executionReceiptIsRequiredAndBindsEvidenceToolsPathsAndUsage() throws Exception {
        WorkerTaskPacket packet = new WorkerTaskPacket("worker-receipt", "turn:run-1", VERSION,
                List.of(new WorkerMaterialAssignment(PAPER, WorkerMaterialType.PAPER)),
                "Compare assigned research materials", List.of("Report receipt facts"),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                        ResearchToolContracts.PROJECT_CODE_SYMBOLS),
                List.of("receipt-evidence"), GENEROUS_BUDGET, List.of());
        ResearchEvidenceRef actual = evidence(VERSION, PAPER, 2, HASH_A);
        ResearchEvidenceRef unobserved = evidence(VERSION, PAPER, 3, HASH_B);
        WorkerFinding finding = new WorkerFinding("receipt-evidence", WorkerMaterialType.PAPER,
                "Receipt-bound finding", List.of(actual));
        WorkerToolObservation observation = new WorkerToolObservation(
                ResearchToolContracts.PROJECT_LATEX_OUTLINE, "Observed LaTeX", List.of(actual));
        long summaryBytes = bytes(finding.summary()) + bytes(observation.summary());
        WorkerResult result = new WorkerResult(packet, WorkerResult.Status.SUCCEEDED,
                List.of(finding), List.of(observation), List.of(), null,
                new WorkerBudgetUsage(1, 1, 1, 1, 100, summaryBytes), List.of(actual));
        WorkerTaskAttestation taskAttestation = WorkerTaskAttestor.attestServerResolved(defaultAuthority(), packet);

        assertThatThrownBy(() -> WorkerResultValidator.attest(taskAttestation, null, result))
                .hasMessageContaining("execution receipt");
        WorkerExecutionReceipt valid = WorkerExecutionReceiptIssuer.recordServerExecution(taskAttestation,
                List.of(PAPER), List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                List.of(actual), 100, 1);
        assertThat(WorkerResultValidator.attest(taskAttestation, valid, result).result()).isEqualTo(result);
        assertThatThrownBy(() -> json.writeValueAsString(valid)).isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> json.readValue("{}", WorkerExecutionReceipt.class))
                .isInstanceOf(JsonProcessingException.class);

        WorkerFinding unobservedFinding = new WorkerFinding("receipt-evidence", WorkerMaterialType.PAPER,
                "Unobserved evidence", List.of(unobserved));
        WorkerResult unobservedResult = new WorkerResult(packet, WorkerResult.Status.SUCCEEDED,
                List.of(unobservedFinding), List.of(), List.of(), null,
                new WorkerBudgetUsage(1, 1, 1, 1, 100, bytes(unobservedFinding.summary())),
                List.of(unobserved));
        assertThatThrownBy(() -> WorkerResultValidator.attest(taskAttestation, valid, unobservedResult))
                .hasMessageContaining("absent from trusted execution facts");

        WorkerExecutionReceipt wrongTool = WorkerExecutionReceiptIssuer.recordServerExecution(taskAttestation,
                List.of(PAPER), List.of(ResearchToolContracts.PROJECT_CODE_SYMBOLS), List.of(actual), 100, 1);
        assertThatThrownBy(() -> WorkerResultValidator.attest(taskAttestation, wrongTool, result))
                .hasMessageContaining("claims a tool absent");
        WorkerExecutionReceipt wrongUsage = WorkerExecutionReceiptIssuer.recordServerExecution(taskAttestation,
                List.of(PAPER), List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), List.of(actual), 99, 1);
        assertThatThrownBy(() -> WorkerResultValidator.attest(taskAttestation, wrongUsage, result))
                .hasMessageContaining("usage does not match");

        assertThatThrownBy(() -> WorkerExecutionReceiptIssuer.recordServerExecution(taskAttestation,
                List.of(CONFIG), List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), List.of(), 1, 1))
                .hasMessageContaining("outside the task assignment");
        assertThatThrownBy(() -> WorkerExecutionReceiptIssuer.recordServerExecution(taskAttestation,
                List.of(), List.of(), List.of(actual), 0, 0))
                .hasMessageContaining("not observed on an inspected path");

        WorkerTaskPacket initialPacket = new WorkerTaskPacket("worker-initial-evidence", "turn:run-1", VERSION,
                List.of(new WorkerMaterialAssignment(PAPER, WorkerMaterialType.PAPER)),
                "Compare assigned research materials", List.of("Use trusted input evidence"), List.of(),
                List.of("receipt-evidence"), GENEROUS_BUDGET, List.of(actual));
        WorkerFinding initialFinding = new WorkerFinding("receipt-evidence", WorkerMaterialType.PAPER,
                "Finding from packet evidence", List.of(actual));
        WorkerResult initialResult = new WorkerResult(initialPacket, WorkerResult.Status.SUCCEEDED,
                List.of(initialFinding), List.of(), List.of(), null,
                new WorkerBudgetUsage(0, 0, 1, 1, 0, bytes(initialFinding.summary())), List.of(actual));
        WorkerTaskAttestation initialTask = WorkerTaskAttestor.attestServerResolved(defaultAuthority(), initialPacket);
        WorkerExecutionReceipt noExecutionFacts = WorkerExecutionReceiptIssuer.recordServerExecution(
                initialTask, List.of(), List.of(), List.of(), 0, 0);
        assertThat(WorkerResultValidator.attest(initialTask, noExecutionFacts, initialResult).result())
                .isEqualTo(initialResult);
    }

    @Test
    void resultJsonRejectsUnknownAuthorityCandidateLegacyAndTampering() throws Exception {
        WorkerResult result = emptySuccess(paperPacket("worker-result-json"));
        ObjectNode base = (ObjectNode) json.readTree(json.writeValueAsString(result));
        for (String field : List.of("completionAuthority", "writeAuthority", "applyAuthority", "candidate",
                "candidateChangeSet", "applyRevision", "userId", "projectId", "chainOfThought")) {
            ObjectNode malicious = base.deepCopy();
            malicious.put(field, true);
            assertThatThrownBy(() -> lenientJson.treeToValue(malicious, WorkerResult.class))
                    .as(field).isInstanceOf(JsonProcessingException.class)
                    .hasMessageContaining("unknown worker contract field");
        }
        for (String required : List.of("workerTaskId", "parentRunId", "projectVersion",
                "taskPacketFingerprint", "status", "findings", "toolObservations",
                "unresolvedQuestions", "failure", "budgetUsage", "evidenceRefs", "fingerprint")) {
            ObjectNode legacy = base.deepCopy();
            legacy.remove(required);
            assertThatThrownBy(() -> json.treeToValue(legacy, WorkerResult.class))
                    .as(required).isInstanceOf(JsonProcessingException.class);
        }
        ObjectNode tampered = base.deepCopy();
        tampered.put("status", "PARTIAL");
        assertThatThrownBy(() -> json.treeToValue(tampered, WorkerResult.class))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("fingerprint");
    }

    @Test
    void multiMaterialAggregationIsDeterministicDeduplicatedAndUnresolvedWithoutRules() throws Exception {
        WorkerServerAuthority authority = defaultAuthority();
        WorkerTaskPacket paperTask = packet("worker-paper", VERSION, List.of(PAPER),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), GENEROUS_BUDGET,
                List.of(), List.of("Inspect paper"));
        WorkerTaskPacket configTask = packet("worker-config", VERSION, List.of(CONFIG),
                List.of(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY), GENEROUS_BUDGET,
                List.of(), List.of("Inspect config"));
        ResearchEvidenceRef paperEvidence = evidence(VERSION, PAPER, 3, HASH_A);
        ResearchEvidenceRef configEvidence = evidence(VERSION, CONFIG, 4, HASH_B);
        WorkerFinding paperFinding = new WorkerFinding("learning-rate", WorkerMaterialType.PAPER,
                "Paper reports 0.01", List.of(paperEvidence));
        WorkerFinding configFinding = new WorkerFinding("learning-rate", WorkerMaterialType.CONFIGURATION,
                "Config sets 0.02", List.of(configEvidence));
        WorkerResult paperResult = resultWithFinding(paperTask, paperFinding, paperEvidence);
        WorkerResult configResult = resultWithFinding(configTask, configFinding, configEvidence);
        WorkerResultAttestation paperAttestation = attest(authority, paperTask, paperResult);
        WorkerResultAttestation configAttestation = attest(authority, configTask, configResult);

        CrossMaterialDifferenceReport first = CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(paperAttestation, configAttestation));
        CrossMaterialDifferenceReport reordered = CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(configAttestation, paperAttestation));

        assertThat(first).isEqualTo(reordered);
        assertThat(first.sourceWorkerTaskIds()).containsExactly("worker-config", "worker-paper");
        assertThat(first.differences()).hasSize(1);
        CrossMaterialDifference difference = first.differences().get(0);
        assertThat(difference.status()).isEqualTo(CrossMaterialDifference.Status.UNRESOLVED);
        assertThat(difference.materialTypes()).containsExactly(
                WorkerMaterialType.PAPER, WorkerMaterialType.CONFIGURATION);
        assertThat(difference.observationSummaries()).containsExactly("Config sets 0.02", "Paper reports 0.01");
        assertThat(difference.evidenceRefs()).containsExactly(configEvidence, paperEvidence);
        assertThat(first.completionAuthority()).isFalse();
        assertThat(first.writeAuthority()).isFalse();
        assertThat(first.applyAuthority()).isFalse();
        assertThat(first.parentTaskCompleted()).isFalse();
        assertThat(first.changesProject()).isFalse();
        assertThat(first.candidateApplicationStatus()).isEqualTo("NOT_APPLIED");
        String serialized = json.writeValueAsString(first);
        assertThat(serialized).doesNotContain("completionAuthority", "writeAuthority", "applyAuthority",
                "candidateApplicationStatus", "projectId", "userId");
        assertThat(json.readValue(serialized, CrossMaterialDifferenceReport.class)).isEqualTo(first);
    }

    @Test
    void aggregationSupportsEmptyAndSingleMaterialAndNeverInfersConsistency() throws Exception {
        WorkerServerAuthority authority = defaultAuthority();
        WorkerTaskPacket emptyTask = packet("worker-empty-result", VERSION, List.of(), List.of(),
                new WorkerBudget(1, 1, 1, 1, 100, 100), List.of(), List.of("Return no findings"));
        WorkerResultAttestation empty = attest(authority, emptyTask, emptySuccess(emptyTask));
        CrossMaterialDifferenceReport emptyReport = CrossMaterialDifferenceAggregator
                .aggregateWithoutTrustedRules(authority, List.of(empty));
        assertThat(emptyReport.differences()).isEmpty();
        assertThat(emptyReport.coverage()).singleElement().satisfies(coverage -> {
            assertThat(coverage.coverageStatus()).isEqualTo(WorkerTaskCoverage.CoverageStatus.INCOMPLETE);
            assertThat(coverage.gaps()).containsExactly(WorkerTaskCoverage.Gap.NO_FINDINGS);
        });

        WorkerTaskPacket paperTask = paperPacket("worker-single-result");
        ResearchEvidenceRef evidence = evidence(VERSION, PAPER, 1, HASH_A);
        WorkerFinding finding = new WorkerFinding("single-claim", WorkerMaterialType.PAPER,
                "Claim appears consistent", List.of(evidence));
        CrossMaterialDifferenceReport single = CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(attest(authority, paperTask,
                        resultWithFinding(paperTask, finding, evidence))));
        assertThat(single.differences()).extracting(CrossMaterialDifference::status)
                .containsExactly(CrossMaterialDifference.Status.UNRESOLVED);
        assertThat(single.coverage()).extracting(WorkerTaskCoverage::coverageStatus)
                .containsExactly(WorkerTaskCoverage.CoverageStatus.COMPLETE);

        ObjectNode reportJson = (ObjectNode) json.readTree(json.writeValueAsString(single));
        ObjectNode forgedBasis = reportJson.deepCopy();
        forgedBasis.put("assessmentBasis", "TRUSTED_RULE_PROJECTION");
        assertThatThrownBy(() -> lenientJson.treeToValue(forgedBasis,
                CrossMaterialDifferenceReport.class)).isInstanceOf(JsonProcessingException.class);
        ObjectNode forgedStatus = reportJson.deepCopy();
        ((ObjectNode) forgedStatus.path("differences").get(0)).put("status", "CONSISTENT");
        assertThatThrownBy(() -> lenientJson.treeToValue(forgedStatus,
                CrossMaterialDifferenceReport.class)).isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void aggregationRejectsUnattestedDuplicateAndCrossBoundaryResults() {
        WorkerServerAuthority authority = defaultAuthority();
        WorkerTaskPacket packet = paperPacket("worker-aggregate-boundary");
        WorkerResult result = emptySuccess(packet);
        WorkerResultAttestation attested = attest(authority, packet, result);

        assertThatThrownBy(() -> CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of())).hasMessageContaining("at least one");
        assertThatThrownBy(() -> CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(result))).hasMessageContaining("only server-attested");
        assertThatThrownBy(() -> CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(attested, attested))).hasMessageContaining("duplicate");

        WorkerServerAuthority otherUser = authority(USER_ID + 1, PROJECT_ID, VERSION, "run-1", ALL_TOOLS,
                GENEROUS_BUDGET);
        WorkerTaskPacket otherUserTask = paperPacket("worker-other-user");
        WorkerResultAttestation otherUserResult = attest(otherUser, otherUserTask,
                emptySuccess(otherUserTask));
        assertThatThrownBy(() -> CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(otherUserResult))).hasMessageContaining("crosses user");

        WorkerServerAuthority otherProject = authority(USER_ID, PROJECT_ID + 1, VERSION, "run-1", ALL_TOOLS,
                GENEROUS_BUDGET);
        WorkerTaskPacket otherProjectTask = paperPacket("worker-other-project");
        WorkerResultAttestation otherProjectResult = attest(otherProject, otherProjectTask,
                emptySuccess(otherProjectTask));
        assertThatThrownBy(() -> CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(otherProjectResult))).hasMessageContaining("crosses user");

        WorkerServerAuthority otherVersion = authority(USER_ID, PROJECT_ID, OTHER_VERSION, "run-1", ALL_TOOLS,
                GENEROUS_BUDGET);
        WorkerTaskPacket otherVersionTask = packet("worker-other-version", OTHER_VERSION,
                List.of(), List.of(), new WorkerBudget(1, 1, 1, 1, 100, 100),
                List.of(), List.of("Return no findings"));
        WorkerResultAttestation otherVersionResult = attest(otherVersion, otherVersionTask,
                emptySuccess(otherVersionTask));
        assertThatThrownBy(() -> CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(otherVersionResult))).hasMessageContaining("crosses user");

        WorkerServerAuthority otherParent = authority(USER_ID, PROJECT_ID, VERSION, "run-2", ALL_TOOLS,
                GENEROUS_BUDGET);
        WorkerTaskPacket otherParentTask = packetForParent("worker-other-parent", "turn:run-2", VERSION);
        WorkerResultAttestation otherParentResult = attest(otherParent, otherParentTask,
                emptySuccess(otherParentTask));
        assertThatThrownBy(() -> CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(otherParentResult))).hasMessageContaining("crosses user");
    }

    @Test
    void coverageExposesFailedCancelledPartialAndNoFindingGaps() {
        WorkerServerAuthority authority = defaultAuthority();
        WorkerTaskPacket failedTask = paperPacket("worker-failed-coverage");
        WorkerTaskPacket cancelledTask = paperPacket("worker-cancelled-coverage");
        WorkerTaskPacket partialTask = paperPacket("worker-partial-coverage");
        WorkerTaskPacket noFindingTask = paperPacket("worker-no-finding-coverage");

        WorkerFailureInfo failedInfo = new WorkerFailureInfo("PARSE_FAILED", "Parser failed", false);
        WorkerResult failed = new WorkerResult(failedTask, WorkerResult.Status.FAILED,
                List.of(), List.of(), List.of(), failedInfo,
                new WorkerBudgetUsage(0, 0, 0, 0, 0, bytes(failedInfo.message())), List.of());
        WorkerFailureInfo cancelledInfo = new WorkerFailureInfo("CANCELLED", "Cancelled by parent", false);
        WorkerResult cancelled = new WorkerResult(cancelledTask, WorkerResult.Status.CANCELLED,
                List.of(), List.of(), List.of(), cancelledInfo,
                new WorkerBudgetUsage(0, 0, 0, 0, 0, bytes(cancelledInfo.message())), List.of());

        ResearchEvidenceRef evidence = evidence(VERSION, PAPER, 7, HASH_A);
        WorkerFinding finding = new WorkerFinding("finding-a", WorkerMaterialType.PAPER,
                "Partial finding", List.of(evidence));
        String question = "A blocking comparison remains";
        WorkerResult partial = new WorkerResult(partialTask, WorkerResult.Status.PARTIAL,
                List.of(finding), List.of(), List.of(question), null,
                new WorkerBudgetUsage(1, 1, 1, 1, 100,
                        bytes(finding.summary()) + bytes(question)), List.of(evidence));
        WorkerResult noFinding = emptySuccess(noFindingTask);

        CrossMaterialDifferenceReport report = CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(attest(authority, failedTask, failed),
                        attest(authority, cancelledTask, cancelled),
                        attest(authority, partialTask, partial),
                        attest(authority, noFindingTask, noFinding)));

        assertThat(report.coverage()).hasSize(4).allSatisfy(item ->
                assertThat(item.coverageStatus()).isEqualTo(WorkerTaskCoverage.CoverageStatus.INCOMPLETE));
        WorkerTaskCoverage failedCoverage = coverage(report, "worker-failed-coverage");
        assertThat(failedCoverage.executionStatus()).isEqualTo(WorkerResult.Status.FAILED);
        assertThat(failedCoverage.failure()).isEqualTo(failedInfo);
        assertThat(failedCoverage.gaps()).containsExactly(WorkerTaskCoverage.Gap.NO_FINDINGS,
                WorkerTaskCoverage.Gap.UNINSPECTED_MATERIALS, WorkerTaskCoverage.Gap.FAILED_OUTCOME);
        WorkerTaskCoverage cancelledCoverage = coverage(report, "worker-cancelled-coverage");
        assertThat(cancelledCoverage.executionStatus()).isEqualTo(WorkerResult.Status.CANCELLED);
        assertThat(cancelledCoverage.gaps()).contains(WorkerTaskCoverage.Gap.CANCELLED_OUTCOME);
        WorkerTaskCoverage partialCoverage = coverage(report, "worker-partial-coverage");
        assertThat(partialCoverage.executionStatus()).isEqualTo(WorkerResult.Status.PARTIAL);
        assertThat(partialCoverage.unresolvedQuestions()).containsExactly(question);
        assertThat(partialCoverage.gaps()).containsExactly(WorkerTaskCoverage.Gap.UNRESOLVED_QUESTIONS,
                WorkerTaskCoverage.Gap.PARTIAL_OUTCOME);
        WorkerTaskCoverage noFindingCoverage = coverage(report, "worker-no-finding-coverage");
        assertThat(noFindingCoverage.gaps()).containsExactly(WorkerTaskCoverage.Gap.NO_FINDINGS,
                WorkerTaskCoverage.Gap.UNINSPECTED_MATERIALS);
        assertThat(report.completionAuthority()).isFalse();
        assertThat(report.writeAuthority()).isFalse();
        assertThat(report.applyAuthority()).isFalse();
    }

    @Test
    void aggregationEnforcesParentTotalBudgetAcrossIndividuallyValidTasks() {
        WorkerBudget oneFindingBudget = new WorkerBudget(2, 2, 1, 2, 1_000, 1_000);
        WorkerServerAuthority authority = authority(USER_ID, PROJECT_ID, VERSION, "run-1", ALL_TOOLS,
                oneFindingBudget);
        WorkerTaskPacket firstTask = packet("worker-budget-a", VERSION, List.of(PAPER),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                oneFindingBudget, List.of(), List.of("Inspect first"));
        WorkerTaskPacket secondTask = packet("worker-budget-b", VERSION, List.of(CONFIG),
                List.of(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY),
                oneFindingBudget, List.of(), List.of("Inspect second"));
        ResearchEvidenceRef paperEvidence = evidence(VERSION, PAPER, 1, HASH_A);
        ResearchEvidenceRef configEvidence = evidence(VERSION, CONFIG, 1, HASH_B);
        WorkerFinding firstFinding = new WorkerFinding("first", WorkerMaterialType.PAPER,
                "First finding", List.of(paperEvidence));
        WorkerFinding secondFinding = new WorkerFinding("second", WorkerMaterialType.CONFIGURATION,
                "Second finding", List.of(configEvidence));
        WorkerResultAttestation first = attest(authority, firstTask,
                resultWithFinding(firstTask, firstFinding, paperEvidence));
        WorkerResultAttestation second = attest(authority, secondTask,
                resultWithFinding(secondTask, secondFinding, configEvidence));

        assertThatThrownBy(() -> CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(first, second))).hasMessageContaining("declared budget");
    }

    @Test
    void differenceReportRejectsCrossVersionEvidenceUnknownFieldsAndTampering() throws Exception {
        WorkerServerAuthority authority = defaultAuthority();
        WorkerTaskPacket packet = paperPacket("worker-report-json");
        ResearchEvidenceRef evidence = evidence(VERSION, PAPER, 1, HASH_A);
        WorkerFinding finding = new WorkerFinding("report", WorkerMaterialType.PAPER,
                "Report finding", List.of(evidence));
        CrossMaterialDifferenceReport report = CrossMaterialDifferenceAggregator.aggregateWithoutTrustedRules(
                authority, List.of(attest(authority, packet, resultWithFinding(packet, finding, evidence))));
        ObjectNode base = (ObjectNode) json.readTree(json.writeValueAsString(report));

        ObjectNode authorityField = base.deepCopy();
        authorityField.put("completionAuthority", true);
        assertThatThrownBy(() -> lenientJson.treeToValue(authorityField,
                CrossMaterialDifferenceReport.class)).isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("unknown worker contract field");
        ObjectNode forgedCoverage = base.deepCopy();
        ((ObjectNode) forgedCoverage.path("coverage").get(0)).put("completionAuthority", true);
        assertThatThrownBy(() -> lenientJson.treeToValue(forgedCoverage,
                CrossMaterialDifferenceReport.class)).isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("unknown worker contract field");
        ObjectNode tampered = base.deepCopy();
        tampered.put("parentRunId", "turn:run-9");
        assertThatThrownBy(() -> json.treeToValue(tampered, CrossMaterialDifferenceReport.class))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("fingerprint");

        CrossMaterialDifference crossVersion = new CrossMaterialDifference("cross-version",
                CrossMaterialDifference.Status.UNRESOLVED, List.of(WorkerMaterialType.PAPER),
                List.of("Cross version"), List.of("worker-report-json"),
                List.of(evidence(OTHER_VERSION, PAPER, 1, HASH_A)));
        assertThatThrownBy(() -> new CrossMaterialDifferenceReport(authority.parentRunId(), VERSION,
                List.of("worker-report-json"), List.of(crossVersion), report.coverage(),
                report.aggregateUsage()))
                .hasMessageContaining("different Project version");
    }

    private WorkerTaskPacket packetWithObjective(String objective) {
        return new WorkerTaskPacket("worker-text", "turn:run-1", VERSION, List.of(), objective,
                List.of("Return a summary"), List.of(), List.of(),
                new WorkerBudget(1, 1, 1, 1, 100, 100), List.of());
    }

    private WorkerTaskPacket paperPacket(String taskId) {
        return packet(taskId, VERSION, List.of(PAPER),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), GENEROUS_BUDGET,
                List.of(), List.of("Inspect paper"));
    }

    private WorkerTaskPacket packet(String taskId, ProjectVersionRef version,
                                    List<ProjectRelativePath> scope, List<String> tools,
                                    WorkerBudget budget, List<ResearchEvidenceRef> evidence,
                                    List<String> criteria) {
        return new WorkerTaskPacket(taskId, "turn:run-1", version, assignments(scope),
                "Compare assigned research materials", criteria, tools, ALL_FINDING_KEYS, budget, evidence);
    }

    private WorkerTaskPacket packetForParent(String taskId, String parentRunId, ProjectVersionRef version) {
        return new WorkerTaskPacket(taskId, parentRunId, version, List.of(),
                "Compare assigned research materials", List.of("Return no findings"), List.of(), List.of(),
                new WorkerBudget(1, 1, 1, 1, 100, 100), List.of());
    }

    private WorkerServerAuthority defaultAuthority() {
        return authority(USER_ID, PROJECT_ID, VERSION, "run-1", ALL_TOOLS, GENEROUS_BUDGET);
    }

    private WorkerServerAuthority authority(long userId, long projectId, ProjectVersionRef version,
                                            String runSourceId, List<String> tools, WorkerBudget budget) {
        return WorkerServerAuthority.serverResolved(identity(userId, projectId, runSourceId),
                new ResearchRuntimeScope(projectId, userId,
                        Set.of(WorkerServerAuthority.REQUIRED_READ_CAPABILITY), version), tools, budget);
    }

    private AgentRunIdentity identity(long userId, long projectId, String runSourceId) {
        return new AgentRunIdentity("turn", runSourceId, userId, 9L, projectId);
    }

    private WorkerResult emptySuccess(WorkerTaskPacket packet) {
        return new WorkerResult(packet, WorkerResult.Status.SUCCEEDED, List.of(), List.of(), List.of(),
                null, zeroUsage(), List.of());
    }

    private WorkerResult resultWithFinding(WorkerTaskPacket packet, WorkerFinding finding,
                                           ResearchEvidenceRef evidence) {
        return new WorkerResult(packet, WorkerResult.Status.SUCCEEDED, List.of(finding, finding),
                List.of(), List.of(), null,
                new WorkerBudgetUsage(1, 1, 1, 1, 100, bytes(finding.summary())),
                List.of(evidence, evidence));
    }

    private WorkerResultAttestation attest(WorkerServerAuthority authority, WorkerTaskPacket packet,
                                           WorkerResult result) {
        WorkerTaskAttestation taskAttestation = WorkerTaskAttestor.attestServerResolved(authority, packet);
        return WorkerResultValidator.attest(taskAttestation, receipt(taskAttestation, result), result);
    }

    private WorkerExecutionReceipt receipt(WorkerTaskAttestation taskAttestation, WorkerResult result) {
        WorkerTaskPacket packet = taskAttestation.packet();
        List<ProjectRelativePath> inspected = packet.materialScope().stream()
                .limit(result.budgetUsage().inputPaths()).toList();
        List<String> tools = result.toolObservations().stream()
                .map(WorkerToolObservation::toolName).distinct().sorted().toList();
        if (tools.isEmpty() && result.budgetUsage().toolCalls() > 0) {
            tools = List.of(packet.allowedReadTools().get(0));
        }
        Set<ProjectRelativePath> inspectedSet = Set.copyOf(inspected);
        List<ResearchEvidenceRef> observed = result.evidenceRefs().stream()
                .filter(item -> inspectedSet.contains(item.relativePath())).toList();
        return WorkerExecutionReceiptIssuer.recordServerExecution(taskAttestation, inspected, tools, observed,
                result.budgetUsage().bytesInspected(), result.budgetUsage().toolCalls());
    }

    private static List<WorkerMaterialAssignment> assignments(List<ProjectRelativePath> paths) {
        return paths.stream().map(path -> new WorkerMaterialAssignment(path, materialType(path))).toList();
    }

    private static WorkerMaterialType materialType(ProjectRelativePath path) {
        if (path.equals(PAPER)) return WorkerMaterialType.PAPER;
        if (path.equals(CONFIG)) return WorkerMaterialType.CONFIGURATION;
        if (path.equals(CODE)) return WorkerMaterialType.CODE;
        return WorkerMaterialType.OTHER;
    }

    private static WorkerTaskCoverage coverage(CrossMaterialDifferenceReport report, String taskId) {
        return report.coverage().stream().filter(item -> item.workerTaskId().equals(taskId))
                .findFirst().orElseThrow();
    }

    private static WorkerBudgetUsage zeroUsage() {
        return new WorkerBudgetUsage(0, 0, 0, 0, 0, 0);
    }

    private static ResearchEvidenceRef evidence(ProjectVersionRef version, ProjectRelativePath path,
                                                int line, FileHash hash) {
        return new ResearchEvidenceRef(version, path, hash, new SourceRange(line, line),
                new ParserVersionRef("worker-test@1"), TrustLabel.SERVER_ATTESTED_METADATA);
    }

    private static ProjectRelativePath path(String value) {
        return ProjectRelativePath.of(value);
    }

    private static long bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
