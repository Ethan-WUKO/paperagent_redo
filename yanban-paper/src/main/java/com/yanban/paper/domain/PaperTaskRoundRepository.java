package com.yanban.paper.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperTaskRoundRepository extends JpaRepository<PaperTaskRound, Long> {
    List<PaperTaskRound> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    Optional<PaperTaskRound> findByTaskIdAndRoundNumberAndStage(Long taskId, Integer roundNumber, String stage);
}
