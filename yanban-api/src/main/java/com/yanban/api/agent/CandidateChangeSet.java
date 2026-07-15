package com.yanban.api.agent;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectVersionRef;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * Legacy single-file response projection retained for source and historical JSON compatibility.
 * New Candidates use the versioned multi-file sandbox artifact response and never derive this
 * value from assistant text.
 */
@Deprecated(forRemoval = false)
public record CandidateChangeSet(
        Long projectId,
        String projectVersion,
        String relativePath,
        @JsonAlias("baseVersion") String fileHash,
        String summary,
        String patchOrSuggestion,
        List<String> evidenceRefs,
        CandidateChangeStatus status,
        String applicationStatus,
        Long artifactId
) {
    public static final String NOT_APPLIED = "NOT_APPLIED";

    public CandidateChangeSet {
        if (projectId == null || !StringUtils.hasText(relativePath) || !StringUtils.hasText(fileHash)) {
            throw new IllegalArgumentException("candidate changes require projectId, relativePath and fileHash");
        }
        if (StringUtils.hasText(projectVersion)) {
            projectVersion = new ProjectVersionRef(projectVersion).value();
            fileHash = new FileHash(fileHash).sha256();
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
        this(projectId, null, relativePath, baseVersion, summary, patchOrSuggestion, evidenceRefs, status, applicationStatus, null);
    }

    public CandidateChangeSet(Long projectId, String relativePath, String baseVersion, String summary,
                              String patchOrSuggestion, List<String> evidenceRefs,
                              CandidateChangeStatus status, String applicationStatus, Long artifactId) {
        this(projectId, null, relativePath, baseVersion, summary, patchOrSuggestion, evidenceRefs,
                status, applicationStatus, artifactId);
    }

    public CandidateChangeSet(Long projectId, String projectVersion, String relativePath, String fileHash,
                              String summary, String patchOrSuggestion, List<String> evidenceRefs,
                              CandidateChangeStatus status, String applicationStatus) {
        this(projectId, projectVersion, relativePath, fileHash, summary, patchOrSuggestion, evidenceRefs,
                status, applicationStatus, null);
    }

    /** Legacy accessor retained for serialized/client compatibility; it is the file hash, not ProjectVersion. */
    public String baseVersion() { return fileHash; }

    /** A base version mismatch makes a candidate non-applicable without touching the Project. */
    public CandidateChangeSet revalidate(EvidenceLedger currentEvidence) {
        boolean current = currentEvidence != null && currentEvidence.evidence().stream().anyMatch(ref ->
                ref.sourceType() == EvidenceSourceType.PROJECT
                        && relativePath.equals(ref.file())
                        && projectVersion != null && projectVersion.equals(ref.projectVersion())
                        && fileHash.equals(ref.fileHash())
                        && ProjectEvidenceValidator.isTrusted(ref));
        return new CandidateChangeSet(projectId, projectVersion, relativePath, fileHash, summary, patchOrSuggestion,
                evidenceRefs, current ? CandidateChangeStatus.CANDIDATE : CandidateChangeStatus.STALE, NOT_APPLIED, artifactId);
    }

    public CandidateChangeSet withArtifactId(Long value) {
        return new CandidateChangeSet(projectId, projectVersion, relativePath, fileHash, summary, patchOrSuggestion, evidenceRefs,
                status, applicationStatus, value);
    }
}
