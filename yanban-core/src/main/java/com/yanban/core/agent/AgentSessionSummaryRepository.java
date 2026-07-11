package com.yanban.core.agent;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionSummaryRepository extends JpaRepository<AgentSessionSummary, Long> {
    Optional<AgentSessionSummary> findBySessionIdAndUserId(Long sessionId, Long userId);

    void deleteBySessionId(Long sessionId);
}
