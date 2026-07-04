package com.yanban.paper.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperTaskRepository extends JpaRepository<PaperTask, Long> {
    List<PaperTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    java.util.Optional<PaperTask> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}
