package com.yanban.core.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentLongTermMemoryRepository extends JpaRepository<AgentLongTermMemory, Long> {
    Optional<AgentLongTermMemory> findByIdAndUserId(Long id, Long userId);

    List<AgentLongTermMemory> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, String status, Pageable page);

    List<AgentLongTermMemory> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable page);
}
