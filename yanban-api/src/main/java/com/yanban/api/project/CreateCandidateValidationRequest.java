package com.yanban.api.project;

import java.util.List;

public record CreateCandidateValidationRequest(CandidateValidationProfile profile,
                                               List<Integer> acceptedChangeIndexes,
                                               boolean confirmed) {
    public CreateCandidateValidationRequest {
        acceptedChangeIndexes = acceptedChangeIndexes == null ? List.of() : List.copyOf(acceptedChangeIndexes);
    }
}
