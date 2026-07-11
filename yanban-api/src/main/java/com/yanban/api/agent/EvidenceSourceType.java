package com.yanban.api.agent;

import java.util.Locale;

/** Source classes are all untrusted model data, never runtime policy. */
public enum EvidenceSourceType {
    RAG,
    PROJECT,
    WEB,
    TOOL,
    LEGACY_UNVERSIONED;

    public static EvidenceSourceType fromLegacySource(String source) {
        if (source == null) {
            return LEGACY_UNVERSIONED;
        }
        return switch (source.trim().toUpperCase(Locale.ROOT)) {
            case "RAG" -> RAG;
            case "PROJECT" -> PROJECT;
            case "WEB" -> WEB;
            case "TOOL" -> TOOL;
            default -> LEGACY_UNVERSIONED;
        };
    }
}
