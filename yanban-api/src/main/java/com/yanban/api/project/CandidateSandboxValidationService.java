package com.yanban.api.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.SandboxCommandPolicy;
import com.yanban.api.agent.sandbox.SandboxExecutionProperties;
import com.yanban.api.artifact.AgentArtifactService;
import com.yanban.api.artifact.ArtifactResponse;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.sandbox.contract.SandboxCanonicalDigest;
import com.yanban.sandbox.contract.SandboxDispatch;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Explicit-user Candidate validation orchestration over the governed Worker 14 Broker boundary. */
@Service
@ConditionalOnProperty(prefix = "yanban.sandbox", name = "enabled", havingValue = "true")
public class CandidateSandboxValidationService {
    private static final TypeReference<List<Integer>> INTEGER_LIST = new TypeReference<>() { };
    private static final String POLICY_VERSION = "candidate-validation-v1";

    private final CandidateSandboxValidationRepository validations;
    private final CandidateChangeArtifactService candidates;
    private final AgentArtifactService artifacts;
    private final ProjectService projects;
    private final SandboxExecutionProperties properties;
    private final SandboxCommandPolicy commands;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public CandidateSandboxValidationService(CandidateSandboxValidationRepository validations,
                                             CandidateChangeArtifactService candidates,
                                             AgentArtifactService artifacts,
                                             ProjectService projects,
                                             SandboxExecutionProperties properties,
                                             SandboxCommandPolicy commands,
                                             ObjectMapper json,
                                             JdbcTemplate jdbc) {
        this.validations = validations; this.candidates = candidates; this.artifacts = artifacts;
        this.projects = projects; this.properties = properties; this.commands = commands; this.json = json; this.jdbc = jdbc;
    }

    public CandidateValidationResponse create(Long userId, Long projectId, Long artifactId,
                                              String idempotencyKey, String ifMatch,
                                              CreateCandidateValidationRequest request) {
        requireIdentity(userId, projectId, artifactId);
        String key = requireIdempotencyKey(idempotencyKey);
        String expectedVersion = requireIfMatch(ifMatch);
        if (request == null || !request.confirmed()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Sandbox validation requires explicit user confirmation");
        }
        CandidateValidationProfile profile = request.profile() == null
                ? CandidateValidationProfile.MAVEN_TEST : request.profile();
        List<Integer> accepted = sortedDistinctNonEmpty(request.acceptedChangeIndexes());
        String requestHash = sha256(projectId + "\n" + artifactId + "\n" + expectedVersion + "\n"
                + profile.name() + "\n" + write(accepted) + "\nconfirmed");
        CandidateSandboxValidation replay = validations
                .findByUserIdAndProjectIdAndIdempotencyKey(userId, projectId, key).orElse(null);
        if (replay != null) return replay(replay, requestHash);

        CandidateArtifactResponse candidate = candidates.getCurrent(userId, artifactId);
        requireApplicableCandidate(projectId, expectedVersion, candidate);
        requireAcceptedIndexes(candidate.changes().size(), accepted);
        ArtifactResponse artifact = artifacts.getArtifact(userId, artifactId);
        if (artifact.sessionId() == null || artifact.sessionId() < 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Candidate is not bound to a Project session");
        }

        ProjectManifestResponse manifest = projects.manifest(userId, projectId);
        if (!expectedVersion.equals(manifest.version())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project current version changed");
        }
        Set<String> allPaths = manifest.files().stream().map(ProjectFileEntry::path)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        ProjectService.SandboxWorkspaceMaterialization materialized =
                projects.materializeSandbox(userId, projectId, allPaths);
        if (!expectedVersion.equals(materialized.snapshot().workspace().projectVersion().value())
                || materialized.textFiles().size() != manifest.files().size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The complete trusted ProjectVersion cannot be materialized for validation");
        }
        Map<String, String> files = applyCandidate(manifest, materialized.textFiles(), candidate, accepted);
        List<String> argv = validationArgv(profile, candidate, accepted);
        commands.validate(argv, Map.of());

        String validationId = UUID.randomUUID().toString();
        long brokerStepId = positiveCorrelation(validationId);
        String policyDigest = sha256(POLICY_VERSION + "\n" + profile.name());
        String brokerKey = "candidate-validation:" + validationId;
        SandboxDispatch unsigned = new SandboxDispatch(brokerKey, "", userId, projectId, artifact.sessionId(),
                artifactId, brokerStepId, 1L, expectedVersion, policyDigest, files, argv,
                properties.getCpus(), properties.getMemoryLimit().toBytes(),
                properties.getExecutionTimeout().toMillis(), properties.getMaxOutputSize().toBytes(), false);
        SandboxDispatch dispatch = new SandboxDispatch(unsigned.idempotencyKey(),
                SandboxCanonicalDigest.compute(unsigned), unsigned.userId(), unsigned.projectId(), unsigned.sessionId(),
                unsigned.planId(), unsigned.stepId(), unsigned.fence(), unsigned.projectVersion(),
                unsigned.policyDigest(), unsigned.files(), unsigned.argv(), unsigned.cpus(), unsigned.memoryBytes(),
                unsigned.timeoutMillis(), unsigned.maxOutputBytes(), false);
        String acceptedJson = write(accepted);
        CandidateSandboxValidation created = new CandidateSandboxValidation(validationId, userId, projectId,
                artifact.sessionId(), artifactId, expectedVersion, candidate.fingerprint().sha256(), acceptedJson,
                sha256(acceptedJson), profile.name(), key, requestHash, dispatch.requestDigest(), policyDigest,
                write(dispatch), dbNow());
        try {
            return response(validations.saveAndFlush(created));
        } catch (DataIntegrityViolationException race) {
            CandidateSandboxValidation winner = validations
                    .findByUserIdAndProjectIdAndIdempotencyKey(userId, projectId, key).orElseThrow(() -> race);
            return replay(winner, requestHash);
        }
    }

    @Transactional(readOnly = true)
    public List<CandidateValidationResponse> list(Long userId, Long projectId, Long artifactId) {
        requireIdentity(userId, projectId, artifactId);
        ArtifactResponse artifact = artifacts.getArtifact(userId, artifactId);
        if (!CandidateChangeArtifactService.SOURCE_TYPE.equals(artifact.sourceType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate not found");
        }
        List<CandidateSandboxValidation> stored = validations
                .findByUserIdAndProjectIdAndArtifactIdOrderByCreatedAtDescIdDesc(userId, projectId, artifactId);
        if (stored.isEmpty() && candidates.getCurrent(userId, artifactId).projectId() != projectId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate not found");
        }
        return stored.stream().map(this::response).toList();
    }

    @Transactional
    public CandidateValidationResponse cancel(Long userId, Long projectId, String validationId) {
        CandidateSandboxValidation value = ownedLocked(userId, projectId, validationId);
        value.requestCancel(dbNow());
        return response(validations.saveAndFlush(value));
    }

    @Transactional
    public CandidateValidationResponse reject(Long userId, Long projectId, String validationId) {
        CandidateSandboxValidation value = ownedLocked(userId, projectId, validationId);
        if ("APPLIED".equals(value.decisionStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Applied validation cannot be rejected");
        }
        value.reject(dbNow());
        return response(validations.saveAndFlush(value));
    }

    CandidateValidationResponse response(CandidateSandboxValidation value) {
        SandboxReceipt receipt = readReceipt(value.receiptJson());
        return new CandidateValidationResponse(value.validationId(), value.projectId(), value.artifactId(),
                value.projectVersion(), value.candidateFingerprint(), readIndexes(value.acceptedChangeIndexesJson()),
                CandidateValidationProfile.valueOf(value.profile()), value.status(), receipt == null ? null : receipt.exitCode(),
                receipt != null && receipt.status().name().equals("TIMED_OUT"), receipt == null ? null : receipt.provider(),
                receipt == null ? "" : receipt.stdout(), receipt == null ? "" : receipt.stderr(),
                receipt != null && receipt.outputTruncated(), value.requestDigest(), value.receiptDigest(), value.errorCode(),
                value.analysisSummary(), value.analysisDisclaimer(), value.decisionStatus(),
                value.applicationOperationId(), value.appliedRevisionId(), value.createdAt(), value.updatedAt());
    }

    private CandidateValidationResponse replay(CandidateSandboxValidation value, String requestHash) {
        if (!value.requestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency-Key is already bound to a different validation request");
        }
        return response(value);
    }

    private Map<String, String> applyCandidate(ProjectManifestResponse manifest, Map<String, String> baseFiles,
                                                CandidateArtifactResponse candidate, List<Integer> accepted) {
        Map<String, ProjectFileEntry> entries = new LinkedHashMap<>();
        manifest.files().stream().sorted(Comparator.comparing(ProjectFileEntry::path))
                .forEach(entry -> entries.put(entry.path(), entry));
        Map<String, String> result = new LinkedHashMap<>(baseFiles);
        for (Integer index : accepted) {
            CandidateFileChange change = candidate.changes().get(index);
            String path = change.relativePath().value();
            ProjectFileEntry base = entries.get(path);
            if (change.type() == CandidateFileChange.Type.ADD) {
                if (base != null || result.containsKey(path)) invalid("Candidate ADD target already exists");
                result.put(path, change.candidateText().text());
                continue;
            }
            if (base == null || change.baseFileHash() == null
                    || !change.baseFileHash().sha256().equals(base.sha256())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Candidate base file hash changed");
            }
            if (change.type() == CandidateFileChange.Type.DELETE) result.remove(path);
            else result.put(path, change.candidateText().text());
        }
        return Map.copyOf(result);
    }

    private List<String> validationArgv(CandidateValidationProfile profile, CandidateArtifactResponse candidate,
                                        List<Integer> accepted) {
        if (profile != CandidateValidationProfile.JAVA_SOURCE_RUN) return profile.argv(null);
        if (accepted.size() != 1) invalid("JAVA_SOURCE_RUN requires exactly one selected Candidate change");
        CandidateFileChange change = candidate.changes().get(accepted.get(0));
        String path = change.relativePath().value();
        if (change.type() == CandidateFileChange.Type.DELETE || !path.endsWith(".java")) {
            invalid("JAVA_SOURCE_RUN requires one added or modified Java source");
        }
        return profile.argv(path);
    }

    private void requireApplicableCandidate(Long projectId, String expectedVersion, CandidateArtifactResponse candidate) {
        if (candidate.projectId() != projectId || !candidate.projectVersion().value().equals(expectedVersion)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Candidate does not match the current Project version");
        }
        if (candidate.governanceStatus() != CandidateChangeSet.GovernanceStatus.VALIDATED
                || !candidate.validation().valid()
                || candidate.applicationStatus() != CandidateChangeSet.ApplicationStatus.NOT_APPLIED) {
            invalid("Candidate is stale or invalid and cannot be validated");
        }
    }

    private CandidateSandboxValidation ownedLocked(Long userId, Long projectId, String validationId) {
        if (validationId == null || validationId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        CandidateSandboxValidation value = validations.lockByValidationId(validationId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate validation not found"));
        if (!value.userId().equals(userId) || !value.projectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate validation not found");
        }
        return value;
    }

    private List<Integer> sortedDistinctNonEmpty(List<Integer> indexes) {
        if (indexes == null || indexes.isEmpty() || indexes.stream().anyMatch(java.util.Objects::isNull)) {
            invalid("At least one Candidate change must be selected");
        }
        TreeSet<Integer> sorted = new TreeSet<>(indexes);
        if (sorted.size() != indexes.size()) invalid("Candidate selection contains duplicate indexes");
        return List.copyOf(sorted);
    }
    private void requireAcceptedIndexes(int size, List<Integer> accepted) {
        if (accepted.stream().anyMatch(index -> index < 0 || index >= size)) invalid("Candidate selection index is invalid");
    }
    private String requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key is required");
        }
        return value;
    }
    private String requireIfMatch(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid If-Match ProjectVersion is required");
        }
        return normalized;
    }
    private void requireIdentity(Long userId, Long projectId, Long artifactId) {
        if (userId == null || projectId == null || projectId < 1 || artifactId == null || artifactId < 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
    private void invalid(String message) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
    private long positiveCorrelation(String validationId) {
        long value = UUID.fromString(validationId).getMostSignificantBits() & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Candidate validation serialization failed", exception); }
    }
    private List<Integer> readIndexes(String value) {
        try { return List.copyOf(json.readValue(value, INTEGER_LIST)); }
        catch (Exception exception) { throw new IllegalStateException("Stored Candidate validation selection is invalid", exception); }
    }
    private SandboxReceipt readReceipt(String value) {
        if (value == null) return null;
        try { return json.readValue(value, SandboxReceipt.class); }
        catch (Exception exception) { throw new IllegalStateException("Stored Candidate validation receipt is invalid", exception); }
    }
    private LocalDateTime dbNow() { return jdbc.queryForObject("select current_timestamp", LocalDateTime.class); }
    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
