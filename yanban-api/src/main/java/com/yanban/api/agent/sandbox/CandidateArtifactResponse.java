package com.yanban.api.agent.sandbox;

import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import com.yanban.core.agent.sandbox.CandidateValidationResult;
import com.yanban.core.research.ProjectVersionRef;
import java.util.List;

/** Authoritative multi-file review response. It carries no path, object key, or runtime authority. */
public record CandidateArtifactResponse(
        String schemaVersion,
        Long artifactId,
        long projectId,
        ProjectVersionRef projectVersion,
        CandidateFingerprint fingerprint,
        CandidateChangeSet.GovernanceStatus governanceStatus,
        CandidateChangeSet.ApplicationStatus applicationStatus,
        List<CandidateFileChange> changes,
        CandidateReviewDiff reviewDiff,
        CandidateValidationResult validation
) {
    public CandidateArtifactResponse {
        if (!CandidateArtifactEnvelope.SCHEMA_VERSION.equals(schemaVersion) || artifactId == null || artifactId < 1
                || projectId < 1 || projectVersion == null || fingerprint == null || governanceStatus == null
                || applicationStatus != CandidateChangeSet.ApplicationStatus.NOT_APPLIED || changes == null
                || changes.isEmpty() || reviewDiff == null || validation == null) {
            throw new IllegalArgumentException("candidate artifact response is incomplete");
        }
        changes = List.copyOf(changes);
        if (!fingerprint.equals(reviewDiff.sourceCandidateFingerprint())
                || !fingerprint.equals(validation.candidateFingerprint())) {
            throw new IllegalArgumentException("candidate artifact response projections do not match");
        }
    }

    public static CandidateArtifactResponse from(Long artifactId, CandidateChangeSet candidate,
                                                 CandidateValidationResult validation) {
        return new CandidateArtifactResponse(CandidateArtifactEnvelope.SCHEMA_VERSION, artifactId,
                candidate.workspace().projectId(), candidate.workspace().projectVersion(), candidate.fingerprint(),
                candidate.governanceStatus(), candidate.applicationStatus(), candidate.changes(),
                CandidateReviewDiff.derive(candidate), validation);
    }
}
