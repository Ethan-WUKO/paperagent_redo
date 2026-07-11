package com.yanban.api.agent;

/** Candidate changes are never applied by this runtime. */
public enum CandidateChangeStatus {
    CANDIDATE,
    STALE,
    INVALIDATED
}
