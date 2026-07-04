package com.yanban.api.memory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LongTermMemoryResponse(
        Long id,
        Long userId,
        Long projectId,
        String scope,
        String memoryType,
        String content,
        List<String> tags,
        String sourceType,
        String sourceRefId,
        BigDecimal confidence,
        String status,
        Long supersedesMemoryId,
        Long supersededByMemoryId,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
