package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.sandbox.CandidateArtifactEnvelope;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.CandidateIntent;
import com.yanban.api.artifact.AgentArtifactService;
import com.yanban.api.artifact.ArtifactResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateValidationResult;
import com.yanban.core.agent.sandbox.SandboxFileSnapshot;
import com.yanban.core.agent.sandbox.SandboxWorkspaceRef;
import com.yanban.core.agent.sandbox.SandboxWorkspaceSnapshot;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectManifestIdentity;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CandidateChangeArtifactServiceTest {
    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 42L;
    private final AgentArtifactService artifacts = mock(AgentArtifactService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CandidateChangeArtifactService service =
            new CandidateChangeArtifactService(artifacts, projects, json);

    @Test
    void generatesValidatesPersistsAndReattestsSortedMultiFileCandidate() throws Exception {
        ProjectService.SandboxWorkspaceMaterialization workspace = workspace(Map.of(
                "src/Main.java", "old\n",
                "docs/remove.txt", "remove\n"));
        String version = workspace.snapshot().workspace().projectVersion().value();
        String mainHash = hash("old\n");
        String removeHash = hash("remove\n");
        EvidenceRef evidence = evidence("trusted-tool:42:src/Main.java:read-1", version,
                "src/Main.java", mainHash, EvidenceVersionStatus.VERIFIED);
        CandidateIntent intent = new CandidateIntent(PROJECT_ID, new ProjectVersionRef(version), List.of(
                new CandidateIntent.FileIntent(CandidateIntent.Type.MODIFY,
                        new ProjectRelativePath("src/Main.java"), new FileHash(mainHash), "updated\n", List.of(evidence.id())),
                new CandidateIntent.FileIntent(CandidateIntent.Type.ADD,
                        new ProjectRelativePath("new.txt"), null, "new\n", List.of(evidence.id())),
                new CandidateIntent.FileIntent(CandidateIntent.Type.DELETE,
                        new ProjectRelativePath("docs/remove.txt"), new FileHash(removeHash), null, List.of(evidence.id()))));
        AtomicReference<String> persisted = arrangePersistence(workspace);

        CandidateArtifactResponse stored = service.store(USER_ID, 1L, context(), intent,
                new EvidenceLedger(List.of(evidence)));

        assertThat(stored.schemaVersion()).isEqualTo(CandidateArtifactEnvelope.SCHEMA_VERSION);
        assertThat(stored.artifactId()).isEqualTo(9L);
        assertThat(stored.governanceStatus()).isEqualTo(CandidateChangeSet.GovernanceStatus.VALIDATED);
        assertThat(stored.applicationStatus()).isEqualTo(CandidateChangeSet.ApplicationStatus.NOT_APPLIED);
        assertThat(stored.changes()).extracting(change -> change.relativePath().value())
                .containsExactly("docs/remove.txt", "new.txt", "src/Main.java");
        assertThat(stored.changes()).extracting(CandidateFileChange::type)
                .containsExactly(CandidateFileChange.Type.DELETE, CandidateFileChange.Type.ADD,
                        CandidateFileChange.Type.MODIFY);
        assertThat(stored.changes().get(0).baseFileHash().sha256()).isEqualTo(removeHash);
        assertThat(stored.changes().get(1).resultFileHash().sha256()).isEqualTo(hash("new\n"));
        assertThat(stored.changes().get(2).baseFileHash().sha256()).isEqualTo(mainHash);
        assertThat(stored.changes().get(2).resultFileHash().sha256()).isEqualTo(hash("updated\n"));
        assertThat(stored.reviewDiff().entries()).hasSize(3);
        assertThat(stored.validation().valid()).isTrue();
        assertThat(persisted.get()).doesNotContain("applicationStatus\":\"APPLIED");

        when(artifacts.getArtifact(USER_ID, 9L)).thenReturn(artifact(9L, persisted.get(),
                CandidateChangeArtifactService.SOURCE_TYPE));
        CandidateArtifactEnvelope restoredEnvelope = json.readValue(persisted.get(), CandidateArtifactEnvelope.class);
        assertThat(restoredEnvelope.candidate().governanceStatus()).isEqualTo(CandidateChangeSet.GovernanceStatus.DRAFT);

        CandidateArtifactResponse reloaded = service.getCurrent(USER_ID, 9L);

        assertThat(reloaded.governanceStatus()).isEqualTo(CandidateChangeSet.GovernanceStatus.VALIDATED);
        assertThat(reloaded.fingerprint()).isEqualTo(stored.fingerprint());
        assertThat(reloaded.applicationStatus()).isEqualTo(CandidateChangeSet.ApplicationStatus.NOT_APPLIED);
    }

    @Test
    void currentManifestChangeReturnsStaleWithoutPartialValidation() throws Exception {
        ProjectService.SandboxWorkspaceMaterialization original = workspace(Map.of("src/Main.java", "old\n"));
        String version = original.snapshot().workspace().projectVersion().value();
        String fileHash = hash("old\n");
        EvidenceRef evidence = evidence("trusted-tool:42:src/Main.java:read-1", version,
                "src/Main.java", fileHash, EvidenceVersionStatus.VERIFIED);
        CandidateIntent intent = modifyIntent(version, fileHash, evidence.id());
        AtomicReference<String> persisted = arrangePersistence(original);
        service.store(USER_ID, 1L, context(), intent, new EvidenceLedger(List.of(evidence)));
        when(artifacts.getArtifact(USER_ID, 9L)).thenReturn(artifact(9L, persisted.get(),
                CandidateChangeArtifactService.SOURCE_TYPE));
        when(projects.materializeSandbox(eq(USER_ID), eq(PROJECT_ID), anySet()))
                .thenReturn(workspace(Map.of("src/Main.java", "externally changed\n")));

        CandidateArtifactResponse reloaded = service.getCurrent(USER_ID, 9L);

        assertThat(reloaded.governanceStatus()).isEqualTo(CandidateChangeSet.GovernanceStatus.STALE);
        assertThat(reloaded.validation().hasIssue(CandidateValidationResult.Code.PROJECT_VERSION_STALE)).isTrue();
        assertThat(reloaded.validation().valid()).isFalse();
        assertThat(reloaded.applicationStatus()).isEqualTo(CandidateChangeSet.ApplicationStatus.NOT_APPLIED);
    }

    @Test
    void persistedValidationProjectionCannotPromoteOrRedirectRevalidation() throws Exception {
        ProjectService.SandboxWorkspaceMaterialization workspace = workspace(Map.of("src/Main.java", "old\n"));
        String version = workspace.snapshot().workspace().projectVersion().value();
        String fileHash = hash("old\n");
        EvidenceRef evidence = evidence("trusted-tool:42:src/Main.java:read-1", version,
                "src/Main.java", fileHash, EvidenceVersionStatus.VERIFIED);
        AtomicReference<String> persisted = arrangePersistence(workspace);
        service.store(USER_ID, 1L, context(), modifyIntent(version, fileHash, evidence.id()),
                new EvidenceLedger(List.of(evidence)));
        ObjectNode forged = (ObjectNode) json.readTree(persisted.get());
        ((ObjectNode) forged.path("validation")).put("snapshotProjectVersion", "f".repeat(64));
        when(artifacts.getArtifact(USER_ID, 9L)).thenReturn(artifact(9L, json.writeValueAsString(forged),
                CandidateChangeArtifactService.SOURCE_TYPE));

        CandidateArtifactResponse reloaded = service.getCurrent(USER_ID, 9L);

        assertThat(reloaded.validation().snapshotProjectVersion().value()).isEqualTo(version);
        assertThat(reloaded.governanceStatus()).isEqualTo(CandidateChangeSet.GovernanceStatus.VALIDATED);
    }

    @Test
    void unknownAttestationAndLegacySingleFileArtifactsFailClosed() throws Exception {
        ProjectService.SandboxWorkspaceMaterialization workspace = workspace(Map.of("src/Main.java", "old\n"));
        String version = workspace.snapshot().workspace().projectVersion().value();
        String fileHash = hash("old\n");
        EvidenceRef evidence = evidence("trusted-tool:42:src/Main.java:read-1", version,
                "src/Main.java", fileHash, EvidenceVersionStatus.VERIFIED);
        AtomicReference<String> persisted = arrangePersistence(workspace);
        service.store(USER_ID, 1L, context(), modifyIntent(version, fileHash, evidence.id()),
                new EvidenceLedger(List.of(evidence)));
        ObjectNode forged = (ObjectNode) json.readTree(persisted.get());
        forged.putObject("attestation").put("trusted", true);
        when(artifacts.getArtifact(USER_ID, 9L)).thenReturn(artifact(9L, json.writeValueAsString(forged),
                CandidateChangeArtifactService.SOURCE_TYPE));

        assertUnprocessable(() -> service.getCurrent(USER_ID, 9L));

        ObjectNode forgedResultHash = (ObjectNode) json.readTree(persisted.get());
        ((ObjectNode) forgedResultHash.path("candidate").path("changes").get(0))
                .put("resultFileHash", "f".repeat(64));
        when(artifacts.getArtifact(USER_ID, 11L)).thenReturn(artifact(11L,
                json.writeValueAsString(forgedResultHash), CandidateChangeArtifactService.SOURCE_TYPE));
        assertUnprocessable(() -> service.getCurrent(USER_ID, 11L));

        CandidateChangeSetLegacyFixture legacy = new CandidateChangeSetLegacyFixture(PROJECT_ID, version,
                "src/Main.java", fileHash, "patch", "NOT_APPLIED");
        when(artifacts.getArtifact(USER_ID, 10L)).thenReturn(artifact(10L, json.writeValueAsString(legacy),
                CandidateChangeArtifactService.SOURCE_TYPE));
        assertUnprocessable(() -> service.getCurrent(USER_ID, 10L));
    }

    @Test
    void staleLegacyCrossProjectAndCrossUserEvidenceAreRejectedBeforePersistence() throws Exception {
        ProjectService.SandboxWorkspaceMaterialization workspace = workspace(Map.of("src/Main.java", "old\n"));
        String version = workspace.snapshot().workspace().projectVersion().value();
        String fileHash = hash("old\n");
        when(projects.materializeSandbox(eq(USER_ID), eq(PROJECT_ID), anySet())).thenReturn(workspace);

        EvidenceRef stale = evidence("trusted-tool:42:src/Main.java:stale", version,
                "src/Main.java", fileHash, EvidenceVersionStatus.STALE);
        assertUnprocessable(() -> service.store(USER_ID, 1L, context(),
                modifyIntent(version, fileHash, stale.id()), new EvidenceLedger(List.of(stale))));

        EvidenceRef legacy = new EvidenceRef("trusted-tool:42:legacy", EvidenceSourceType.PROJECT,
                "PROJECT", "src/Main.java", "tool:legacy", null, fileHash, "legacy");
        assertUnprocessable(() -> service.store(USER_ID, 1L, context(),
                modifyIntent(version, fileHash, legacy.id()), new EvidenceLedger(List.of(legacy))));

        EvidenceRef foreign = evidence("trusted-tool:99:src/Main.java:foreign", version,
                "src/Main.java", fileHash, EvidenceVersionStatus.VERIFIED);
        assertUnprocessable(() -> service.store(USER_ID, 1L, context(),
                modifyIntent(version, fileHash, foreign.id()), new EvidenceLedger(List.of(foreign))));

        assertUnprocessable(() -> service.store(8L, 1L, context(),
                modifyIntent(version, fileHash, stale.id()), new EvidenceLedger(List.of(stale))));
        verify(artifacts, never()).createCandidateArtifact(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void inputBudgetsFailBeforeProjectReadAndNeverPersistPartially() {
        List<CandidateIntent.FileIntent> tooMany = new ArrayList<>();
        for (int index = 0; index <= CandidateChangeArtifactService.VALIDATION_BUDGET.maxChanges(); index++) {
            tooMany.add(new CandidateIntent.FileIntent(CandidateIntent.Type.ADD,
                    new ProjectRelativePath("new-" + index + ".txt"), null, "x", List.of("e" + index)));
        }
        CandidateIntent intent = new CandidateIntent(PROJECT_ID, new ProjectVersionRef("a".repeat(64)), tooMany);

        assertThatThrownBy(() -> service.store(USER_ID, 1L, context(), intent, EvidenceLedger.empty()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

        List<String> tooManyEvidence = new ArrayList<>();
        for (int index = 0; index <= CandidateChangeArtifactService.VALIDATION_BUDGET.maxEvidenceRefs(); index++) {
            tooManyEvidence.add("e" + index);
        }
        CandidateIntent evidenceHeavy = new CandidateIntent(PROJECT_ID, new ProjectVersionRef("a".repeat(64)),
                List.of(new CandidateIntent.FileIntent(CandidateIntent.Type.ADD,
                        new ProjectRelativePath("evidence.txt"), null, "x", tooManyEvidence)));
        assertThatThrownBy(() -> service.store(USER_ID, 1L, context(), evidenceHeavy, EvidenceLedger.empty()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

        String oversizedText = "x".repeat((int) CandidateChangeArtifactService.VALIDATION_BUDGET
                .maxCandidateUtf8Bytes() + 1);
        CandidateIntent byteHeavy = new CandidateIntent(PROJECT_ID, new ProjectVersionRef("a".repeat(64)),
                List.of(new CandidateIntent.FileIntent(CandidateIntent.Type.ADD,
                        new ProjectRelativePath("large.txt"), null, oversizedText, List.of("e1"))));
        assertThatThrownBy(() -> service.store(USER_ID, 1L, context(), byteHeavy, EvidenceLedger.empty()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(projects, never()).materializeSandbox(anyLong(), anyLong(), anySet());
        verify(artifacts, never()).createCandidateArtifact(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void baseHashMismatchFailsClosedWithoutArtifact() throws Exception {
        ProjectService.SandboxWorkspaceMaterialization workspace = workspace(Map.of("src/Main.java", "old\n"));
        String version = workspace.snapshot().workspace().projectVersion().value();
        String actualHash = hash("old\n");
        EvidenceRef evidence = evidence("trusted-tool:42:src/Main.java:read-1", version,
                "src/Main.java", actualHash, EvidenceVersionStatus.VERIFIED);
        when(projects.materializeSandbox(eq(USER_ID), eq(PROJECT_ID), anySet())).thenReturn(workspace);
        CandidateIntent wrongBase = new CandidateIntent(PROJECT_ID, new ProjectVersionRef(version), List.of(
                new CandidateIntent.FileIntent(CandidateIntent.Type.MODIFY,
                        new ProjectRelativePath("src/Main.java"), new FileHash("f".repeat(64)),
                        "updated\n", List.of(evidence.id()))));

        assertUnprocessable(() -> service.store(USER_ID, 1L, context(), wrongBase,
                new EvidenceLedger(List.of(evidence))));
        verify(artifacts, never()).createCandidateArtifact(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void intentRejectsDuplicateCaseVariantPathsAndSortsDeterministically() {
        ProjectVersionRef version = new ProjectVersionRef("a".repeat(64));
        CandidateIntent sorted = new CandidateIntent(PROJECT_ID, version, List.of(
                new CandidateIntent.FileIntent(CandidateIntent.Type.ADD, new ProjectRelativePath("z.txt"),
                        null, "z", List.of("e1")),
                new CandidateIntent.FileIntent(CandidateIntent.Type.ADD, new ProjectRelativePath("a.txt"),
                        null, "a", List.of("e1"))));
        assertThat(sorted.changes()).extracting(change -> change.relativePath().value())
                .containsExactly("a.txt", "z.txt");
        assertThatThrownBy(() -> new CandidateIntent(PROJECT_ID, version, List.of(
                new CandidateIntent.FileIntent(CandidateIntent.Type.ADD, new ProjectRelativePath("A.txt"),
                        null, "a", List.of("e1")),
                new CandidateIntent.FileIntent(CandidateIntent.Type.ADD, new ProjectRelativePath("a.txt"),
                        null, "b", List.of("e1")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AtomicReference<String> arrangePersistence(ProjectService.SandboxWorkspaceMaterialization workspace) {
        when(projects.materializeSandbox(eq(USER_ID), eq(PROJECT_ID), anySet())).thenReturn(workspace);
        AtomicReference<String> persisted = new AtomicReference<>();
        when(artifacts.createCandidateArtifact(eq(USER_ID), anyLong(), anyString(), anyString())).thenAnswer(call -> {
            String content = call.getArgument(3);
            persisted.set(content);
            return artifact(9L, content, CandidateChangeArtifactService.SOURCE_TYPE);
        });
        return persisted;
    }

    private CandidateIntent modifyIntent(String version, String fileHash, String evidenceId) {
        return new CandidateIntent(PROJECT_ID, new ProjectVersionRef(version), List.of(
                new CandidateIntent.FileIntent(CandidateIntent.Type.MODIFY,
                        new ProjectRelativePath("src/Main.java"), new FileHash(fileHash),
                        "updated\n", List.of(evidenceId))));
    }

    private EvidenceRef evidence(String id, String version, String path, String fileHash,
                                 EvidenceVersionStatus status) {
        return new EvidenceRef(id, EvidenceSourceType.PROJECT, "PROJECT", path, "tool:read", null,
                fileHash, "test", version, fileHash, 1, 1, "project-read-file@1", status);
    }

    private ProjectRuntimeContext context() {
        return new ProjectRuntimeContext(USER_ID, PROJECT_ID);
    }

    private ProjectService.SandboxWorkspaceMaterialization workspace(Map<String, String> contents) throws Exception {
        Map<String, String> sorted = new java.util.TreeMap<>(contents);
        List<SandboxFileSnapshot> files = new ArrayList<>();
        Map<String, String> text = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            files.add(new SandboxFileSnapshot(new ProjectRelativePath(entry.getKey()),
                    new FileHash(hash(entry.getValue())), bytes.length));
            text.put(entry.getKey(), entry.getValue());
        }
        ProjectVersionRef version = ProjectManifestIdentity.derive(files.stream().map(file ->
                new ProjectManifestIdentity.Entry(file.relativePath(), file.fileHash(), file.sizeBytes())).toList());
        return new ProjectService.SandboxWorkspaceMaterialization(
                new SandboxWorkspaceSnapshot(new SandboxWorkspaceRef(PROJECT_ID, version), files), text);
    }

    private String hash(String content) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private ArtifactResponse artifact(Long id, String content, String sourceType) {
        return new ArtifactResponse(id, USER_ID, 1L, "candidate.json", "TEXT", content,
                sourceType, List.of(), "ACTIVE", null, null, null, Instant.EPOCH, Instant.EPOCH);
    }

    private void assertUnprocessable(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private record CandidateChangeSetLegacyFixture(long projectId, String projectVersion,
                                                   String relativePath, String fileHash,
                                                   String patchOrSuggestion, String applicationStatus) {
    }
}
