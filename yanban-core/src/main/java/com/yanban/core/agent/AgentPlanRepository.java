package com.yanban.core.agent;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface AgentPlanRepository extends JpaRepository<AgentPlan, Long> {
    Optional<AgentPlan> findByIdAndUserId(Long id, Long userId);

    List<AgentPlan> findBySessionIdAndUserIdOrderByCreatedAtDesc(Long sessionId, Long userId);

    List<AgentPlan> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AgentPlan p where p.id = :id and p.userId = :userId")
    Optional<AgentPlan> findLockedByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("select current_timestamp from AgentPlan p where p.id = :id")
    LocalDateTime currentDatabaseTime(@Param("id") Long id);

    @Query("select p from AgentPlan p where p.persistenceLevel = 'L2_DURABLE' and ("
            + "(p.status = 'RUNNING' and (p.leaseExpiresAt is null or p.leaseExpiresAt <= current_timestamp)) "
            + "or (p.status = 'REVIEWING' and p.recoveryStatus = 'QUEUED'))")
    List<AgentPlan> findExpiredDurableRuns();
}
