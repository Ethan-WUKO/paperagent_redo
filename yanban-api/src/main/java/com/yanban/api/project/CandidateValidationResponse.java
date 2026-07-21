package com.yanban.api.project;

import java.time.LocalDateTime;
import java.util.List;

public record CandidateValidationResponse(
        String validationId,
        long projectId,
        long artifactId,
        String projectVersion,
        String candidateFingerprint,
        List<Integer> acceptedChangeIndexes,
        CandidateValidationProfile profile,
        String status,
        Integer exitCode,
        boolean timedOut,
        String provider,
        String stdout,
        String stderr,
        boolean outputTruncated,
        String requestDigest,
        String receiptDigest,
        String errorCode,
        String analysisSummary,
        String analysisDisclaimer,
        String decisionStatus,
        Long applicationOperationId,
        Long appliedRevisionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public CandidateValidationResponse {
        acceptedChangeIndexes = acceptedChangeIndexes == null ? List.of() : List.copyOf(acceptedChangeIndexes);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
