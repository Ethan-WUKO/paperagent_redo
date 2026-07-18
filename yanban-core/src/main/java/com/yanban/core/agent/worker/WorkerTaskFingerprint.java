package com.yanban.core.agent.worker;

import com.fasterxml.jackson.annotation.JsonValue;

/** SHA-256 identity of an authority-free Worker task packet. */
public record WorkerTaskFingerprint(@JsonValue String sha256) implements RejectsUnknownFields {
    public WorkerTaskFingerprint {
        if (sha256 == null || !sha256.matches("(?i)^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("worker task fingerprint must be a SHA-256 digest");
        }
        sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
    }
}
