package com.yanban.core.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPlanStepRepository extends JpaRepository<AgentPlanStep, Long> {
    List<AgentPlanStep> findByPlanIdOrderBySortOrderAsc(Long planId);

    void deleteByPlanId(Long planId);
}
