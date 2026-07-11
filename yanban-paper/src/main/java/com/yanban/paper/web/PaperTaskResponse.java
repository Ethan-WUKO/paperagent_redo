package com.yanban.paper.web;

import com.yanban.paper.domain.PaperTask;
import java.time.Instant;

public record PaperTaskResponse(
        Long id,
        Long userId,
        String title,
        String sourceFilename,
        String objectKey,
        String finalObjectKey,
        String clientRequestId,
        Boolean idempotent,
        String status,
        String targetLanguage,
        String currentStage,
        String errorMessage,
        Integer scoreThreshold,
        Integer maxRounds,
        Integer innerMaxAttempts,
        Integer literatureMinCount,
        Integer literatureCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaperTaskResponse from(PaperTask task,
                                         Integer scoreThreshold,
                                         Integer maxRounds,
                                         Integer innerMaxAttempts,
                                         Integer literatureCount,
                                         boolean idempotent) {
        return from(task, literatureCount, idempotent);
    }

    public static PaperTaskResponse from(PaperTask task,
                                         Integer literatureCount,
                                         boolean idempotent) {
        return new PaperTaskResponse(
                task.getId(),
                task.getUserId(),
                task.getTitle(),
                task.getSourceFilename(),
                task.getObjectKey(),
                task.getFinalObjectKey(),
                task.getClientRequestId(),
                idempotent,
                task.getStatus(),
                task.getTargetLanguage(),
                task.getCurrentStage(),
                task.getErrorMessage(),
                task.getScoreThreshold(),
                task.getMaxRounds(),
                task.getInnerMaxAttempts(),
                task.getLiteratureMinCount(),
                literatureCount,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
