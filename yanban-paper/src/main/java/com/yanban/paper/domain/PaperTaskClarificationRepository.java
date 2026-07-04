package com.yanban.paper.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperTaskClarificationRepository extends JpaRepository<PaperTaskClarification, Long> {
    List<PaperTaskClarification> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    List<PaperTaskClarification> findByTaskIdAndStatusOrderByCreatedAtAsc(Long taskId, String status);

    boolean existsByTaskIdAndStatus(Long taskId, String status);
}
