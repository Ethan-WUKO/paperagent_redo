package com.yanban.paper.web;

import com.yanban.paper.domain.PaperTaskClarification;
import java.time.Instant;

public record PaperClarificationResponse(
        Long id,
        Long taskId,
        String type,
        String questionJson,
        String optionsJson,
        String status,
        String userAnswerJson,
        Instant createdAt,
        Instant answeredAt
) {
    public static PaperClarificationResponse from(PaperTaskClarification clarification) {
        return new PaperClarificationResponse(
                clarification.getId(),
                clarification.getTaskId(),
                clarification.getType(),
                clarification.getQuestionJson(),
                clarification.getOptionsJson(),
                clarification.getStatus(),
                clarification.getUserAnswerJson(),
                clarification.getCreatedAt(),
                clarification.getAnsweredAt()
        );
    }
}
