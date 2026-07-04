package com.yanban.core.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskEventRepository extends JpaRepository<AgentTaskEvent, Long> {
    List<AgentTaskEvent> findByTaskTypeAndTaskIdAndUserIdOrderByCreatedAtAsc(String taskType, Long taskId, Long userId);
}
