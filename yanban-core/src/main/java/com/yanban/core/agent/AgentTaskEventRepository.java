package com.yanban.core.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskEventRepository extends JpaRepository<AgentTaskEvent, Long> {
    List<AgentTaskEvent> findByTaskTypeAndTaskIdAndUserIdOrderByCreatedAtAsc(String taskType, Long taskId, Long userId);

    List<AgentTaskEvent> findByTaskTypeAndTaskIdAndUserIdOrderByIdAsc(String taskType,
                                                                      Long taskId,
                                                                      Long userId,
                                                                      Pageable pageable);

    List<AgentTaskEvent> findByTaskTypeAndTaskIdAndUserIdAndIdGreaterThanOrderByIdAsc(String taskType,
                                                                                      Long taskId,
                                                                                      Long userId,
                                                                                      Long afterEventId,
                                                                                      Pageable pageable);

    Optional<AgentTaskEvent> findTopByTaskTypeAndTaskIdAndUserIdOrderByIdDesc(String taskType, Long taskId, Long userId);

    List<AgentTaskEvent> findByCreatedAtAfterOrderByCreatedAtDesc(Instant createdAt);
}
