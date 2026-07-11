package com.yanban.paper.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperTaskLiteratureRepository extends JpaRepository<PaperTaskLiterature, Long> {
    List<PaperTaskLiterature> findByTaskIdOrderByRelevanceScoreDesc(Long taskId);

    Optional<PaperTaskLiterature> findByTaskIdAndCardId(Long taskId, Long cardId);
}
