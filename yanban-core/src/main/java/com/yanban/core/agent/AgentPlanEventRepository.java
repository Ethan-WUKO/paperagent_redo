package com.yanban.core.agent;

import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPlanEventRepository extends JpaRepository<AgentPlanEvent, Long> {
    List<AgentPlanEvent> findByPlanIdOrderByCreatedAtAsc(Long planId);

    List<AgentPlanEvent> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime createdAt);
}
