package com.yanban.api.agent;

import org.springframework.util.StringUtils;

/**
 * Stable, display-safe provenance for one piece of non-runtime context.
 */
public record EvidenceRef(
        String id,
        EvidenceSourceType sourceType,
        String source,
        String file,
        String chunk,
        String citation,
        String version,
        String selectionReason
) {
    public EvidenceRef(String id,
                       String source,
                       String file,
                       String chunk,
                       String citation,
                       String version,
                       String selectionReason) {
        this(id, EvidenceSourceType.fromLegacySource(source), source, file, chunk, citation, version, selectionReason);
    }

    public EvidenceRef {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("evidence id must not be blank");
        }
        if (!StringUtils.hasText(source)) {
            throw new IllegalArgumentException("evidence source must not be blank");
        }
        sourceType = sourceType == null ? EvidenceSourceType.LEGACY_UNVERSIONED : sourceType;
        if ((sourceType == EvidenceSourceType.RAG || sourceType == EvidenceSourceType.PROJECT)
                && (!StringUtils.hasText(file) || !StringUtils.hasText(chunk) || !StringUtils.hasText(version))) {
            throw new IllegalArgumentException(sourceType + " evidence requires file, chunk and version");
        }
    }
}
