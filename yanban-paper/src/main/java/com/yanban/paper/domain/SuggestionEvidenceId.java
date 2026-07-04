package com.yanban.paper.domain;

import java.io.Serializable;
import java.util.Objects;

public class SuggestionEvidenceId implements Serializable {
    private Long suggestionId;
    private Long cardId;

    public SuggestionEvidenceId() {
    }

    public SuggestionEvidenceId(Long suggestionId, Long cardId) {
        this.suggestionId = suggestionId;
        this.cardId = cardId;
    }

    public Long getSuggestionId() { return suggestionId; }
    public Long getCardId() { return cardId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SuggestionEvidenceId that)) return false;
        return Objects.equals(suggestionId, that.suggestionId) && Objects.equals(cardId, that.cardId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(suggestionId, cardId);
    }
}
