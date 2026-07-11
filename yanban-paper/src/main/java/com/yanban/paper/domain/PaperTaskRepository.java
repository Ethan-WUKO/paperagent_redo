package com.yanban.paper.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperTaskRepository extends JpaRepository<PaperTask, Long> {
    List<PaperTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<PaperTask> findByIdAndUserId(Long id, Long userId);

    Optional<PaperTask> findByIdempotencyKey(String idempotencyKey);

    long countByUserId(Long userId);
}
