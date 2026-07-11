package com.yanban.api.agent;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * Auditable proposed Project modification. This is deliberately a response-only value:
 * it contains no filesystem handle and there is no apply operation in the runtime.
 */
public record CandidateChangeSet(
        Long projectId,
        String relativePath,
        String baseVersion,
        String summary,
        String patchOrSuggestion,
        List<String> evidenceRefs,
        CandidateChangeStatus status,
        String applicationStatus,
        Long artifactId
) {
    public static final String NOT_APPLIED = "NOT_APPLIED";

    public CandidateChangeSet {
        if (projectId == null || !StringUtils.hasText(relativePath) || !StringUtils.hasText(baseVersion)) {
            throw new IllegalArgumentException("candidate changes require projectId, relativePath and baseVersion");
        }
        summary = StringUtils.hasText(summary) ? summary.trim() : "Candidate change suggested by runtime output.";
        patchOrSuggestion = StringUtils.hasText(patchOrSuggestion) ? patchOrSuggestion : summary;
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        status = status == null ? CandidateChangeStatus.CANDIDATE : status;
        applicationStatus = NOT_APPLIED;
    }

    public CandidateChangeSet(Long projectId, String relativePath, String baseVersion, String summary,
                              String patchOrSuggestion, List<String> evidenceRefs,
                              CandidateChangeStatus status, String applicationStatus) {
        this(projectId, relativePath, baseVersion, summary, patchOrSuggestion, evidenceRefs, status, applicationStatus, null);
    }

    /** A base version mismatch makes a candidate non-applicable without touching the Project. */
    public CandidateChangeSet revalidate(EvidenceLedger currentEvidence) {
        boolean current = currentEvidence != null && currentEvidence.evidence().stream().anyMatch(ref ->
                ref.sourceType() == EvidenceSourceType.PROJECT
                        && relativePath.equals(ref.file())
                        && baseVersion.equals(ref.version())
                        && ProjectEvidenceValidator.isTrusted(ref));
        return new CandidateChangeSet(projectId, relativePath, baseVersion, summary, patchOrSuggestion,
                evidenceRefs, current ? CandidateChangeStatus.CANDIDATE : CandidateChangeStatus.STALE, NOT_APPLIED, artifactId);
    }

    public CandidateChangeSet withArtifactId(Long value) {
        return new CandidateChangeSet(projectId, relativePath, baseVersion, summary, patchOrSuggestion, evidenceRefs,
                status, applicationStatus, value);
    }
}
