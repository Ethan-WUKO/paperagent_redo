package com.yanban.core.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentContextSnapshotRepository extends JpaRepository<AgentContextSnapshot, Long> {
    Optional<AgentContextSnapshot> findByTurnIdAndSessionIdAndUserId(Long turnId, Long sessionId, Long userId);

    List<AgentContextSnapshot> findBySessionIdAndUserIdOrderByCreatedAtDesc(Long sessionId, Long userId, Pageable page);
}
