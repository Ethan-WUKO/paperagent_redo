package com.yanban.paper.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@IdClass(SuggestionEvidenceId.class)
@Table(name = "suggestion_evidence")
public class SuggestionEvidence {

    @Id
    @Column(name = "suggestion_id", nullable = false)
    private Long suggestionId;

    @Id
    @Column(name = "card_id", nullable = false)
    private Long cardId;

    protected SuggestionEvidence() {
    }

    public SuggestionEvidence(Long suggestionId, Long cardId) {
        this.suggestionId = suggestionId;
        this.cardId = cardId;
    }

    public Long getSuggestionId() { return suggestionId; }
    public Long getCardId() { return cardId; }
}
