package com.yanban.knowledge.eval;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

class FixtureBackedBaselineSearchBackend implements BaselineSearchBackend {

    private final RagSpikeFixtureLoader loader;
    private final List<RagSpikeDocumentFixture> documents;

    FixtureBackedBaselineSearchBackend(RagSpikeFixtureLoader loader, List<RagSpikeDocumentFixture> documents) {
        this.loader = loader;
        this.documents = documents;
    }

    @Override
    public List<BaselineRagHit> search(RagSpikeEvalCase evalCase) {
        return documents.stream()
                .filter(document -> visibleToUser(document, evalCase.userId()))
                .filter(document -> !"DELETED".equalsIgnoreCase(document.versionStatus()))
                .map(document -> toHit(document, evalCase.query()))
                .filter(hit -> hit.score() > 0.0d)
                .sorted(Comparator.comparingDouble(BaselineRagHit::score).reversed())
                .limit(evalCase.topK())
                .toList();
    }

    private boolean visibleToUser(RagSpikeDocumentFixture document, Long userId) {
        return "PUBLIC".equalsIgnoreCase(document.visibility())
                || (document.userId() != null && document.userId().equals(userId));
    }

    private BaselineRagHit toHit(RagSpikeDocumentFixture document, String query) {
        String text;
        try {
            text = loader.readDocumentText(document);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read fixture " + document.path(), ex);
        }
        return new BaselineRagHit(
                document.documentId(),
                document.filename(),
                0,
                text,
                score(text, query, document),
                document.citationId(),
                document.sourceType(),
                document.versionStatus(),
                document.visibility()
        );
    }

    private double score(String text, String query, RagSpikeDocumentFixture document) {
        String normalizedText = normalize(text);
        String normalizedQuery = normalize(query);
        double score = 0.0d;
        for (String token : normalizedQuery.split("\\s+")) {
            if (token.length() >= 4 && normalizedText.contains(token)) {
                score += 1.0d;
            }
        }
        if (document.expectedChunkHints() != null) {
            for (String hint : document.expectedChunkHints()) {
                if (normalizedQuery.contains(normalize(hint)) || normalizedText.contains(normalize(hint))) {
                    score += 0.5d;
                }
            }
        }
        if ("ACTIVE".equalsIgnoreCase(document.versionStatus())) {
            score += 0.25d;
        } else if ("SUPERSEDED".equalsIgnoreCase(document.versionStatus())) {
            score -= 0.25d;
        }
        return Math.max(0.0d, score);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ").trim();
    }
}
