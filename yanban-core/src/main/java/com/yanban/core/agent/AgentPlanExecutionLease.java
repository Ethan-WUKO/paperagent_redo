package com.yanban.core.agent;

import java.time.LocalDateTime;

/** Database-fenced ownership of one durable Agent Plan execution. */
public record AgentPlanExecutionLease(Long planId, Long userId, String owner, String token,
                                      long fence, LocalDateTime expiresAt, boolean recovery) {
    public AgentPlanExecutionLease {
        if (planId == null || userId == null || owner == null || owner.isBlank()
                || token == null || token.isBlank() || fence < 1 || expiresAt == null) {
            throw new IllegalArgumentException("execution lease is incomplete");
        }
    }
}
