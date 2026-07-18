package com.yanban.core.agent.worker;

import com.fasterxml.jackson.annotation.JsonValue;

/** SHA-256 identity of an authority-free cross-material report. */
public record CrossMaterialReportFingerprint(@JsonValue String sha256) implements RejectsUnknownFields {
    public CrossMaterialReportFingerprint {
        if (sha256 == null || !sha256.matches("(?i)^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("cross-material report fingerprint must be a SHA-256 digest");
        }
        sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
    }
}
