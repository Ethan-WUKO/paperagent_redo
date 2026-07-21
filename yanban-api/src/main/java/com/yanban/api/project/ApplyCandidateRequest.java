package com.yanban.api.project;

import java.util.List;

public record ApplyCandidateRequest(List<Integer> acceptedChangeIndexes, String validationId) {
    public ApplyCandidateRequest {
        acceptedChangeIndexes = acceptedChangeIndexes == null ? List.of() : List.copyOf(acceptedChangeIndexes);
    }

    public ApplyCandidateRequest(List<Integer> acceptedChangeIndexes) {
        this(acceptedChangeIndexes, null);
    }
}
