package com.yanban.api.agent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @Size(max = 255) String title,
        String modelProvider,
        String model,
        @Min(1) @Max(100) Integer maxSteps,
        Boolean ragDisabled
) {
}
