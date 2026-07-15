package com.yanban.api.agent.sandbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateValidationResult;

/** Serializable artifact body. Its validation result is audit data, never validation authority. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CandidateArtifactEnvelope(String schemaVersion, CandidateChangeSet candidate,
                                        CandidateValidationResult validation) {
    public static final String SCHEMA_VERSION = "YANBAN_CANDIDATE_ARTIFACT_V1";

    public CandidateArtifactEnvelope {
        if (!SCHEMA_VERSION.equals(schemaVersion) || candidate == null || validation == null) {
            throw new IllegalArgumentException("candidate artifact envelope is incomplete or unsupported");
        }
        if (!candidate.fingerprint().equals(validation.candidateFingerprint())) {
            throw new IllegalArgumentException("candidate artifact projections do not share one identity");
        }
    }
}
