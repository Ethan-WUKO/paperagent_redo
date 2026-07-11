package com.yanban.core.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentArtifactRepository extends JpaRepository<AgentArtifact, Long> {
    Optional<AgentArtifact> findByIdAndUserId(Long id, Long userId);

    List<AgentArtifact> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, String status, Pageable page);

    List<AgentArtifact> findByUserIdAndSessionIdAndStatusOrderByUpdatedAtDesc(Long userId, Long sessionId, String status, Pageable page);
}
