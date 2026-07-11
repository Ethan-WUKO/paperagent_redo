package com.yanban.paper.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuggestionEvidenceRepository extends JpaRepository<SuggestionEvidence, SuggestionEvidenceId> {
    List<SuggestionEvidence> findBySuggestionId(Long suggestionId);

    List<SuggestionEvidence> findBySuggestionIdIn(Collection<Long> suggestionIds);

    void deleteBySuggestionIdIn(Collection<Long> suggestionIds);
}
