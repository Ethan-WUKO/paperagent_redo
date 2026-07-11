package com.yanban.api.agent;

import com.yanban.core.agent.AgentPlan;
import java.time.LocalDateTime;
import java.util.List;

public record AgentPlanResponse(
        Long id,
        Long sessionId,
        String goal,
        String summary,
        String status,
        Boolean ragDisabled,
        String skillId,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        List<AgentPlanStepResponse> steps
) {
    public static AgentPlanResponse from(AgentPlan plan, List<AgentPlanStepResponse> steps) {
        return new AgentPlanResponse(
                plan.getId(),
                plan.getSessionId(),
                plan.getGoal(),
                plan.getSummary(),
                plan.getStatus(),
                plan.getRagDisabled(),
                plan.getSkillId(),
                plan.getErrorMessage(),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                plan.getStartedAt(),
                plan.getFinishedAt(),
                steps
        );
    }
}
