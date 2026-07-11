package com.yanban.paper.web;

import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.PaperTaskLiterature;
import com.yanban.paper.domain.Suggestion;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PaperSuggestionResponse(
        Long id,
        Long taskId,
        Long sectionId,
        String track,
        String category,
        String severity,
        String statement,
        Boolean applicable,
        String patchJson,
        String status,
        String honestyGrade,
        String honestyReason,
        Integer evidenceCount,
        List<EvidenceCardResponse> evidenceCards,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaperSuggestionResponse from(Suggestion suggestion, List<LiteratureCard> evidenceCards) {
        return from(suggestion, evidenceCards, Map.of());
    }

    public static PaperSuggestionResponse from(Suggestion suggestion,
                                               List<LiteratureCard> evidenceCards,
                                               Map<Long, PaperTaskLiterature> literatureByCardId) {
        List<EvidenceCardResponse> cards = evidenceCards == null ? List.of() : evidenceCards.stream()
                .map(card -> EvidenceCardResponse.from(card, literatureByCardId == null ? null : literatureByCardId.get(card.getId())))
                .toList();
        boolean grounded = !cards.isEmpty();
        boolean directPatch = Boolean.TRUE.equals(suggestion.getApplicable()) && "ADVOCACY".equalsIgnoreCase(suggestion.getTrack()) && grounded;
        String grade = directPatch ? "A" : "B";
        String reason = directPatch
                ? "有真实 evidence 和稳定锚点时由系统自动应用，并记录最终插入状态。"
                : grounded && "ADVOCACY".equalsIgnoreCase(suggestion.getTrack())
                        ? "已有真实 evidence，但未匹配到唯一 citation slot 或稳定锚点，因此仅进入报告。"
                        : "缺少可直接采纳的真实 evidence 或属于批评轨，仅作为骨架/审查建议展示。";
        return new PaperSuggestionResponse(
                suggestion.getId(),
                suggestion.getTaskId(),
                suggestion.getSectionId(),
                suggestion.getTrack(),
                suggestion.getCategory(),
                suggestion.getSeverity(),
                suggestion.getStatement(),
                suggestion.getApplicable(),
                suggestion.getPatchJson(),
                suggestion.getStatus(),
                grade,
                reason,
                cards.size(),
                cards,
                suggestion.getCreatedAt(),
                suggestion.getUpdatedAt()
        );
    }

    public static PaperSuggestionResponse from(Suggestion suggestion, int evidenceCount) {
        return from(suggestion, List.of());
    }

    public record EvidenceCardResponse(
            Long id,
            String title,
            String authors,
            Integer publicationYear,
            String venue,
            String doi,
            String arxivId,
            String openAlexId,
            String s2Id,
            String url,
            String pdfUrl,
            Integer citationCount,
            Double relevanceScore,
            String narrativeRole,
            String sourceQuery
    ) {
        public static EvidenceCardResponse from(LiteratureCard card) {
            return from(card, null);
        }

        public static EvidenceCardResponse from(LiteratureCard card, PaperTaskLiterature literature) {
            return new EvidenceCardResponse(
                    card.getId(),
                    card.getTitle(),
                    card.getAuthors(),
                    card.getPublicationYear(),
                    card.getVenue(),
                    card.getDoi(),
                    card.getArxivId(),
                    card.getOpenAlexId(),
                    card.getS2Id(),
                    card.getUrl(),
                    card.getPdfUrl(),
                    card.getCitationCount(),
                    literature == null ? null : literature.getRelevanceScore(),
                    literature == null ? null : literature.getNarrativeRole(),
                    literature == null ? null : literature.getSourceQuery()
            );
        }
    }
}
