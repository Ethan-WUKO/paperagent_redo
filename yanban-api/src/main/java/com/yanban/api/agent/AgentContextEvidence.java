package com.yanban.api.agent;

import org.springframework.util.StringUtils;

/**
 * Untrusted content plus its provenance. This type is intentionally unable to
 * request a system role.
 */
public record AgentContextEvidence(EvidenceRef ref, String content) {
    public AgentContextEvidence {
        if (ref == null) {
            throw new IllegalArgumentException("evidence ref must not be null");
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("evidence content must not be blank");
        }
    }
}
