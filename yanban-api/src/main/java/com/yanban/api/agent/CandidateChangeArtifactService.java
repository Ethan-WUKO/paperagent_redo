package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.CandidateArtifactEnvelope;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.CandidateIntent;
import com.yanban.api.artifact.AgentArtifactService;
import com.yanban.api.artifact.ArtifactResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateTextPayload;
import com.yanban.core.agent.sandbox.CandidateValidationBudget;
import com.yanban.core.agent.sandbox.CandidateValidationDecision;
import com.yanban.core.agent.sandbox.CandidateValidator;
import com.yanban.core.agent.sandbox.SandboxFileSnapshot;
import com.yanban.core.agent.sandbox.SandboxSnapshotAttestation;
import com.yanban.core.agent.sandbox.SandboxSnapshotAttestor;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ParserVersionRef;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ResearchEvidenceRef;
import com.yanban.core.research.ResearchRuntimeScope;
import com.yanban.core.research.SourceRange;
import com.yanban.core.research.TrustLabel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Persists proposal-only multi-file Candidates and re-attests them on every read. */
@Service
public class CandidateChangeArtifactService {
    public static final String SOURCE_TYPE = AgentArtifactService.CANDIDATE_CHANGESET_SOURCE_TYPE;
    static final CandidateValidationBudget VALIDATION_BUDGET =
            new CandidateValidationBudget(32, 128, 256 * 1024L);
    private static final int MAX_ENVELOPE_CHARACTERS = 480_000;

    private final AgentArtifactService artifacts;
    private final ProjectService projects;
    private final ObjectMapper objectMapper;

    public CandidateChangeArtifactService(AgentArtifactService artifacts, ProjectService projects,
                                          ObjectMapper objectMapper) {
        this.artifacts = artifacts;
        this.projects = projects;
        this.objectMapper = objectMapper;
    }

    public CandidateArtifactResponse store(Long userId, Long sessionId, ProjectRuntimeContext context,
                                           CandidateIntent intent, EvidenceLedger evidenceLedger) {
        requireServerScope(userId, context, intent);
        enforceInputBudget(intent);
        Map<String, EvidenceRef> referencedEvidence = resolveEvidence(intent, evidenceLedger);
        Set<String> requestedPaths = requestedPaths(intent, referencedEvidence.values());
        ProjectService.SandboxWorkspaceMaterialization materialized =
                projects.materializeSandbox(userId, intent.projectId(), requestedPaths);
        if (!intent.projectVersion().equals(materialized.snapshot().workspace().projectVersion())) {
            throw invalidCandidate("candidate intent ProjectVersion is stale");
        }

        Map<String, SandboxFileSnapshot> manifest = manifestByPath(materialized);
        List<CandidateFileChange> changes = new ArrayList<>();
        for (CandidateIntent.FileIntent change : intent.changes()) {
            List<ResearchEvidenceRef> evidence = change.evidenceRefIds().stream()
                    .map(referencedEvidence::get)
                    .map(ref -> convertEvidence(intent, ref, manifest, materialized.textFiles()))
                    .toList();
            changes.add(toCoreChange(intent, change, evidence));
        }

        com.yanban.core.agent.sandbox.CandidateChangeSet draft =
                com.yanban.core.agent.sandbox.CandidateChangeSet.draft(materialized.snapshot().workspace(), changes);
        CandidateValidationDecision decision = CandidateValidator.validate(draft,
                attest(userId, materialized), VALIDATION_BUDGET);
        com.yanban.core.agent.sandbox.CandidateChangeSet validated = draft.applyValidation(decision);
        if (validated.governanceStatus()
                != com.yanban.core.agent.sandbox.CandidateChangeSet.GovernanceStatus.VALIDATED) {
            throw invalidCandidate("candidate intent failed deterministic validation");
        }

        CandidateArtifactEnvelope envelope = new CandidateArtifactEnvelope(
                CandidateArtifactEnvelope.SCHEMA_VERSION, validated, decision.result());
        String content = writeEnvelope(envelope);
        ArtifactResponse artifact = artifacts.createCandidateArtifact(userId, sessionId,
                "candidate-" + validated.fingerprint().sha256() + ".json", content);
        return CandidateArtifactResponse.from(artifact.id(), validated, decision.result());
    }

    public CandidateArtifactResponse getCurrent(Long userId, Long artifactId) {
        ArtifactResponse artifact = artifacts.getArtifact(userId, artifactId);
        if (!SOURCE_TYPE.equals(artifact.sourceType())) {
            throw invalidCandidate("artifact is not a Candidate ChangeSet");
        }
        CandidateArtifactEnvelope envelope = readEnvelope(artifact.content());
        com.yanban.core.agent.sandbox.CandidateChangeSet restored = envelope.candidate();
        enforceRestoredBudget(restored);
        rejectUntrustedPersistedEvidence(restored);
        Set<String> requestedPaths = requestedPaths(restored);
        ProjectService.SandboxWorkspaceMaterialization materialized;
        try {
            materialized = projects.materializeSandbox(userId, restored.workspace().projectId(), requestedPaths);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw invalidCandidate("candidate Project is unavailable");
            }
            throw ex;
        }
        if (restored.workspace().projectVersion().equals(materialized.snapshot().workspace().projectVersion())) {
            verifyPersistedEvidenceRanges(restored, materialized.textFiles());
        }
        CandidateValidationDecision decision = CandidateValidator.validate(restored,
                attest(userId, materialized), VALIDATION_BUDGET);
        com.yanban.core.agent.sandbox.CandidateChangeSet current = restored.applyValidation(decision);
        return CandidateArtifactResponse.from(artifact.id(), current, decision.result());
    }

    private void requireServerScope(Long userId, ProjectRuntimeContext context, CandidateIntent intent) {
        if (userId == null || context == null || intent == null || !userId.equals(context.userId())
                || context.projectId() != intent.projectId()) {
            throw invalidCandidate("candidate intent does not match the authenticated Project scope");
        }
    }

    private void enforceInputBudget(CandidateIntent intent) {
        if (intent.changes().size() > VALIDATION_BUDGET.maxChanges()
                || intent.evidenceRefCount() > VALIDATION_BUDGET.maxEvidenceRefs()
                || intent.candidateUtf8Bytes() > VALIDATION_BUDGET.maxCandidateUtf8Bytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "candidate intent exceeds the validation budget");
        }
    }

    private void enforceRestoredBudget(com.yanban.core.agent.sandbox.CandidateChangeSet candidate) {
        int evidenceRefs = candidate.changes().stream()
                .mapToInt(change -> change.evidenceRefs().size()).sum();
        long candidateBytes = candidate.changes().stream()
                .filter(change -> change.candidateText() != null)
                .mapToLong(change -> change.candidateText().utf8Bytes()).sum();
        if (candidate.changes().size() > VALIDATION_BUDGET.maxChanges()
                || evidenceRefs > VALIDATION_BUDGET.maxEvidenceRefs()
                || candidateBytes > VALIDATION_BUDGET.maxCandidateUtf8Bytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "persisted Candidate exceeds the validation budget");
        }
    }

    private Map<String, EvidenceRef> resolveEvidence(CandidateIntent intent, EvidenceLedger ledger) {
        Map<String, EvidenceRef> available = new LinkedHashMap<>();
        if (ledger != null) ledger.evidence().forEach(ref -> available.put(ref.id(), ref));
        Map<String, EvidenceRef> resolved = new LinkedHashMap<>();
        for (CandidateIntent.FileIntent change : intent.changes()) {
            for (String id : change.evidenceRefIds()) {
                EvidenceRef ref = available.get(id);
                if (ref == null) throw invalidCandidate("candidate evidence is not in the trusted ledger");
                requireEvidenceIdentity(intent, ref);
                resolved.put(id, ref);
            }
        }
        return resolved;
    }

    private Set<String> requestedPaths(CandidateIntent intent, java.util.Collection<EvidenceRef> evidence) {
        Set<String> paths = new LinkedHashSet<>();
        intent.changes().forEach(change -> paths.add(change.relativePath().value()));
        evidence.forEach(ref -> {
            if (ref != null && ref.file() != null) paths.add(ref.file());
        });
        return Set.copyOf(paths);
    }

    private Set<String> requestedPaths(com.yanban.core.agent.sandbox.CandidateChangeSet candidate) {
        Set<String> paths = new LinkedHashSet<>();
        candidate.changes().forEach(change -> {
            paths.add(change.relativePath().value());
            change.evidenceRefs().forEach(ref -> paths.add(ref.relativePath().value()));
        });
        return Set.copyOf(paths);
    }

    private Map<String, SandboxFileSnapshot> manifestByPath(
            ProjectService.SandboxWorkspaceMaterialization materialized) {
        Map<String, SandboxFileSnapshot> manifest = new HashMap<>();
        materialized.snapshot().files().forEach(file -> manifest.put(file.relativePath().value(), file));
        return manifest;
    }

    private ResearchEvidenceRef convertEvidence(CandidateIntent intent, EvidenceRef ref,
                                                Map<String, SandboxFileSnapshot> manifest,
                                                Map<String, String> textFiles) {
        requireEvidenceIdentity(intent, ref);
        ProjectRelativePath path = new ProjectRelativePath(ref.file());
        SandboxFileSnapshot file = manifest.get(path.value());
        if (file == null || !file.fileHash().sha256().equals(ref.fileHash())) {
            throw invalidCandidate("candidate evidence does not match the current manifest");
        }
        verifyRange(textFiles.get(path.value()), ref.startLine(), ref.endLine());
        return new ResearchEvidenceRef(intent.projectVersion(), path, new FileHash(ref.fileHash()),
                new SourceRange(ref.startLine(), ref.endLine()), new ParserVersionRef(ref.parserVersion()),
                TrustLabel.UNTRUSTED_PROJECT_CONTENT);
    }

    private void requireEvidenceIdentity(CandidateIntent intent, EvidenceRef ref) {
        String expectedToolPrefix = "trusted-tool:" + intent.projectId() + ":";
        String expectedPlanPrefix = "trusted-plan:" + intent.projectId() + ":";
        boolean trustedId = ref != null && ref.id() != null
                && (ref.id().startsWith(expectedToolPrefix) || ref.id().startsWith(expectedPlanPrefix));
        if (!trustedId || ref.sourceType() != EvidenceSourceType.PROJECT
                || ref.versionStatus() != EvidenceVersionStatus.VERIFIED
                || !ProjectEvidenceValidator.isTrusted(ref)
                || !intent.projectVersion().value().equals(ref.projectVersion())) {
            throw invalidCandidate("candidate evidence is stale, legacy, or outside the Project scope");
        }
    }

    private CandidateFileChange toCoreChange(CandidateIntent intent, CandidateIntent.FileIntent change,
                                             List<ResearchEvidenceRef> evidence) {
        return switch (change.type()) {
            case ADD -> CandidateFileChange.add(intent.projectVersion(), change.relativePath(),
                    CandidateTextPayload.fromText(change.replacementText()), evidence);
            case MODIFY -> CandidateFileChange.modify(intent.projectVersion(), change.relativePath(),
                    change.baseFileHash(), CandidateTextPayload.fromText(change.replacementText()), evidence);
            case DELETE -> CandidateFileChange.delete(intent.projectVersion(), change.relativePath(),
                    change.baseFileHash(), evidence);
        };
    }

    private SandboxSnapshotAttestation attest(Long userId,
                                               ProjectService.SandboxWorkspaceMaterialization materialized) {
        ResearchRuntimeScope scope = new ResearchRuntimeScope(materialized.snapshot().workspace().projectId(),
                userId, Set.of(SandboxSnapshotAttestor.REQUIRED_READ_CAPABILITY),
                materialized.snapshot().workspace().projectVersion());
        return SandboxSnapshotAttestor.attestServerResolved(scope, materialized.snapshot());
    }

    private String writeEnvelope(CandidateArtifactEnvelope envelope) {
        try {
            String content = objectMapper.writeValueAsString(envelope);
            if (content.length() > MAX_ENVELOPE_CHARACTERS) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "candidate artifact exceeds the persistence budget");
            }
            return content;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize candidate artifact", ex);
        }
    }

    private CandidateArtifactEnvelope readEnvelope(String content) {
        try {
            return objectMapper.readValue(content, CandidateArtifactEnvelope.class);
        } catch (Exception ex) {
            throw invalidCandidate("legacy or invalid Candidate artifact is not trusted");
        }
    }

    private void rejectUntrustedPersistedEvidence(
            com.yanban.core.agent.sandbox.CandidateChangeSet candidate) {
        boolean invalid = candidate.changes().stream().flatMap(change -> change.evidenceRefs().stream())
                .anyMatch(ref -> ref.trustLabel() != TrustLabel.UNTRUSTED_PROJECT_CONTENT);
        if (invalid) throw invalidCandidate("persisted Candidate contains an unsupported Evidence trust label");
    }

    private void verifyPersistedEvidenceRanges(
            com.yanban.core.agent.sandbox.CandidateChangeSet candidate, Map<String, String> textFiles) {
        candidate.changes().stream().flatMap(change -> change.evidenceRefs().stream())
                .forEach(ref -> {
                    String content = textFiles.get(ref.relativePath().value());
                    if (content != null) {
                        verifyRange(content, ref.range().startLine(), ref.range().endLine());
                    }
                });
    }

    private void verifyRange(String content, Integer startLine, Integer endLine) {
        if (content == null || startLine == null || startLine < 1 || endLine == null || endLine < startLine) {
            throw invalidCandidate("candidate evidence range is incomplete");
        }
        int lineCount = content.split("\\R", -1).length;
        if (endLine > lineCount) throw invalidCandidate("candidate evidence range exceeds its source file");
    }

    private ResponseStatusException invalidCandidate(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
