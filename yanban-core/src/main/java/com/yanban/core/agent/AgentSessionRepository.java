package com.yanban.core.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {
    List<AgentSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<AgentSession> findByIdAndUserId(Long id, Long userId);
}
