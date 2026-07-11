package com.yanban.core.agent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {
    List<AgentMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<AgentMessage> findBySessionIdOrderByIdDesc(Long sessionId, Pageable pageable);

    List<AgentMessage> findBySessionIdAndIdLessThanOrderByIdDesc(Long sessionId, Long beforeId, Pageable pageable);

    List<AgentMessage> findBySessionIdAndRoleInOrderByIdDesc(Long sessionId, Collection<String> roles, Pageable pageable);

    List<AgentMessage> findBySessionIdAndRoleInAndIdLessThanOrderByIdDesc(Long sessionId, Collection<String> roles, Long beforeId, Pageable pageable);

    long countByUserIdAndRoleAndCreatedAtAfter(Long userId, String role, Instant createdAt);

    void deleteBySessionId(Long sessionId);
}
