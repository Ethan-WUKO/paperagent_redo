package com.yanban.core.agent;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {
    Optional<AgentTask> findByTaskTypeAndSourceAndSourceId(String taskType, String source, Long sourceId);

    Optional<AgentTask> findByTaskTypeAndSourceAndSourceIdAndUserId(String taskType,
                                                                    String source,
                                                                    Long sourceId,
                                                                    Long userId);

    List<AgentTask> findBySourceIdAndUserIdAndTaskTypeIn(Long sourceId, Long userId, Collection<String> taskTypes);
}
