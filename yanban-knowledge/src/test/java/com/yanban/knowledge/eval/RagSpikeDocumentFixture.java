package com.yanban.knowledge.eval;

import java.util.List;

public record RagSpikeDocumentFixture(
        Long documentId,
        Long userId,
        Long projectId,
        String visibility,
        String sourceType,
        String versionStatus,
        String lineageId,
        String filename,
        String path,
        String citationId,
        List<String> expectedChunkHints
) {
}
