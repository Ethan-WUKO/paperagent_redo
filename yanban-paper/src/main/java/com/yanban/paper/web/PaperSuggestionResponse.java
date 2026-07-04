package com.yanban.paper.web;

import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.Suggestion;
import java.time.Instant;
import java.util.List;

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
        List<EvidenceCardResponse> cards = evidenceCards == null ? List.of() : evidenceCards.stream()
                .map(EvidenceCardResponse::from)
                .toList();
        boolean grounded = !cards.isEmpty();
        boolean directPatch = Boolean.TRUE.equals(suggestion.getApplicable()) && "ADVOCACY".equalsIgnoreCase(suggestion.getTrack()) && grounded;
        String grade = directPatch ? "A" : "B";
        String reason = directPatch
                ? "有真实 evidence 支撑，可作为候选补丁逐条采纳。"
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
            Integer citationCount
    ) {
        public static EvidenceCardResponse from(LiteratureCard card) {
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
                    card.getCitationCount()
            );
        }
    }
}
