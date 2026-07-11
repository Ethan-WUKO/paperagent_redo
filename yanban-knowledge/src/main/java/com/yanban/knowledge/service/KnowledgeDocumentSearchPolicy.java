package com.yanban.knowledge.service;

import com.yanban.knowledge.domain.KbDocument;
import java.util.Locale;

final class KnowledgeDocumentSearchPolicy {

    private KnowledgeDocumentSearchPolicy() {
    }

    static boolean canInject(KbDocument document, KnowledgeSearchOptions options) {
        if (document == null || options == null) {
            return false;
        }
        if (!"READY".equalsIgnoreCase(nullToBlank(document.getStatus()))) {
            return false;
        }
        if (!isVisibleToUser(document, options.userId())) {
            return false;
        }
        if (!isProjectAllowed(document, options.projectId())) {
            return false;
        }
        String versionStatus = normalizedVersionStatus(document);
        if ("DELETED".equals(versionStatus) || "ARCHIVED".equals(versionStatus)) {
            return false;
        }
        return "ACTIVE".equals(versionStatus)
                || (options.includeSuperseded() && "SUPERSEDED".equals(versionStatus));
    }

    static double scoreBoost(KbDocument document) {
        if (document == null) {
            return 0.0d;
        }
        return sourceTypeBoost(document.getSourceType()) + versionBoost(normalizedVersionStatus(document));
    }

    static KnowledgeSearchResult toResult(KbDocument document,
                                          Integer chunkIndex,
                                          String chunkText,
                                          double baseScore) {
        return new KnowledgeSearchResult(
                document.getId(),
                document.getFilename(),
                chunkIndex,
                chunkText,
                baseScore + scoreBoost(document),
                Boolean.TRUE.equals(document.getIsPublic()),
                document.getSourceType(),
                normalizedVersionStatus(document),
                document.getLineageId(),
                document.getVersionNo(),
                document.getProjectId(),
                document.getCanonicalKey()
        );
    }

    private static boolean isVisibleToUser(KbDocument document, Long userId) {
        return Boolean.TRUE.equals(document.getIsPublic())
                || (userId != null && userId.equals(document.getUserId()));
    }

    private static boolean isProjectAllowed(KbDocument document, Long projectId) {
        return projectId == null
                || document.getProjectId() == null
                || projectId.equals(document.getProjectId());
    }

    private static double versionBoost(String versionStatus) {
        return switch (versionStatus) {
            case "ACTIVE" -> 0.20d;
            case "SUPERSEDED" -> -0.50d;
            default -> 0.0d;
        };
    }

    private static double sourceTypeBoost(String sourceType) {
        return switch (normalize(sourceType)) {
            case "PAPER_POLISHED" -> 0.20d;
            case "LITERATURE_CARD" -> 0.15d;
            case "PAPER_ORIGINAL", "PUBLIC_NOTE" -> 0.05d;
            default -> 0.0d;
        };
    }

    private static String normalizedVersionStatus(KbDocument document) {
        String versionStatus = document == null ? null : document.getVersionStatus();
        return normalize(versionStatus).isBlank() ? "ACTIVE" : normalize(versionStatus);
    }

    private static String normalize(String value) {
        return nullToBlank(value).toUpperCase(Locale.ROOT);
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value.trim();
    }
}
