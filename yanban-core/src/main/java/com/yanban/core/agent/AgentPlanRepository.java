package com.yanban.core.agent;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPlanRepository extends JpaRepository<AgentPlan, Long> {
    Optional<AgentPlan> findByIdAndUserId(Long id, Long userId);

    List<AgentPlan> findBySessionIdAndUserIdOrderByCreatedAtDesc(Long sessionId, Long userId);

    List<AgentPlan> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime createdAt);
}
