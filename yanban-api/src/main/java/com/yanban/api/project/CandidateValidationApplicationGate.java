package com.yanban.api.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Fail-closed receipt gate between Candidate validation and the existing Worker 9 revision workflow. */
@Service
public class CandidateValidationApplicationGate {
    private static final TypeReference<List<Integer>> INTEGER_LIST = new TypeReference<>() { };
    private final CandidateSandboxValidationRepository validations;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public CandidateValidationApplicationGate(CandidateSandboxValidationRepository validations,
                                              ObjectMapper json, JdbcTemplate jdbc) {
        this.validations = validations; this.json = json; this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public void requireSuccessful(Long userId, Long projectId, Long artifactId, String validationId,
                                  String projectVersion, CandidateArtifactResponse candidate,
                                  List<Integer> acceptedChangeIndexes) {
        if (validationId == null || validationId.isBlank()) invalid("A successful Candidate validation is required");
        CandidateSandboxValidation value = validations
                .findByValidationIdAndUserIdAndProjectId(validationId, userId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Candidate validation was not found"));
        List<Integer> storedIndexes = readIndexes(value.acceptedChangeIndexesJson());
        SandboxReceipt receipt = readReceipt(value.receiptJson());
        if (!value.artifactId().equals(artifactId) || !value.projectVersion().equals(projectVersion)
                || !value.candidateFingerprint().equals(candidate.fingerprint().sha256())
                || !storedIndexes.equals(acceptedChangeIndexes)
                || !value.selectionDigest().equals(CandidateSandboxValidationService.sha256(write(storedIndexes)))
                || !"SUCCEEDED".equals(value.status()) || !"PENDING".equals(value.decisionStatus())
                || receipt == null || receipt.status() != SandboxExecutionStatus.SUCCEEDED
                || receipt.exitCode() == null || receipt.exitCode() != 0 || receipt.errorCode() != null
                || !"docker-sbx".equals(receipt.provider())
                || !value.requestDigest().equals(receipt.requestDigest())
                || !value.projectVersion().equals(receipt.projectVersion())
                || value.userId() != receipt.userId() || value.projectId() != receipt.projectId()
                || value.sessionId() != receipt.sessionId() || value.artifactId() != receipt.planId()
                || receipt.fence() != 1L) {
            invalid("Candidate validation is stale, unsuccessful, rejected, or bound to different content");
        }
    }

    @Transactional
    public void markApplied(String validationId, Long operationId, Long revisionId) {
        CandidateSandboxValidation value = validations.lockByValidationId(validationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Candidate validation disappeared before application"));
        if (!"PENDING".equals(value.decisionStatus()) || !"SUCCEEDED".equals(value.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Candidate validation is no longer available for application");
        }
        value.applied(operationId, revisionId, dbNow());
        validations.saveAndFlush(value);
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
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private LocalDateTime dbNow() { return jdbc.queryForObject("select current_timestamp", LocalDateTime.class); }
    private void invalid(String message) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
}
