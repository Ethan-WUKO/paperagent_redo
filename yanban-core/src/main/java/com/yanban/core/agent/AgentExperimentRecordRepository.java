package com.yanban.core.agent;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExperimentRecordRepository extends JpaRepository<AgentExperimentRecord, Long> {
    List<AgentExperimentRecord> findBySessionIdAndUserIdOrderByCreatedAtDesc(Long sessionId, Long userId, Pageable page);
}
