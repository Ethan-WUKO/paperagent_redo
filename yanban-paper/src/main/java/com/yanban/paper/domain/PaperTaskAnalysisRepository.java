package com.yanban.paper.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperTaskAnalysisRepository extends JpaRepository<PaperTaskAnalysis, Long> {
    Optional<PaperTaskAnalysis> findByTaskId(Long taskId);
}
