package com.yanban.core.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentToolRunRepository extends JpaRepository<AgentToolRun, Long> {
    List<AgentToolRun> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
