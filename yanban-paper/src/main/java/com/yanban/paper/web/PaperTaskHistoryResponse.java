package com.yanban.paper.web;

import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import java.time.Instant;
import java.util.List;

public record PaperTaskHistoryResponse(
        Long id,
        String title,
        String sourceFilename,
        String status,
        String currentStage,
        String errorMessage,
        String targetLanguage,
        String finalObjectKey,
        Integer literatureMinCount,
        Integer literatureCount,
        Instant createdAt,
        Instant updatedAt,
        List<PaperArtifactResponse> artifacts
) {
    public static PaperTaskHistoryResponse from(PaperTask task, List<PaperTaskArtifact> artifacts) {
        return new PaperTaskHistoryResponse(
                task.getId(),
                task.getTitle(),
                task.getSourceFilename(),
                task.getStatus(),
                task.getCurrentStage(),
                task.getErrorMessage(),
                task.getTargetLanguage(),
                task.getFinalObjectKey(),
                task.getLiteratureMinCount(),
                task.getLiteratureCount(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                artifacts.stream().map(PaperArtifactResponse::from).toList()
        );
    }
}
