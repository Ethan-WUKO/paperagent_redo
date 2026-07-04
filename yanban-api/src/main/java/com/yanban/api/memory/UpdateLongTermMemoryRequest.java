package com.yanban.api.memory;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;

public record UpdateLongTermMemoryRequest(
        Long projectId,
        String scope,
        String memoryType,
        @NotBlank String content,
        List<String> tags,
        BigDecimal confidence
) {
}
