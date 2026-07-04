package com.yanban.paper.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperSectionRepository extends JpaRepository<PaperSection, Long> {
    List<PaperSection> findByTaskIdOrderByOrderIndexAsc(Long taskId);

    Optional<PaperSection> findByIdAndTaskId(Long id, Long taskId);

    void deleteByTaskId(Long taskId);
}
