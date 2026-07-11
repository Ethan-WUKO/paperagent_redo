package com.yanban.paper.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuggestionRepository extends JpaRepository<Suggestion, Long> {
    List<Suggestion> findByTaskIdOrderByCreatedAt(Long taskId);

    void deleteByTaskId(Long taskId);
}
