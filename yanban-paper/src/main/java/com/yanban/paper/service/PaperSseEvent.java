package com.yanban.paper.service;

import java.time.Instant;

public record PaperSseEvent(
        String type,
        Long taskId,
        String message,
        String stage,
        Instant timestamp,
        Integer currentSection,
        Integer totalSections,
        String sectionTitle,
        Integer attempt,
        Integer maxAttempts,
        Integer progressPercent
) {
    public static PaperSseEvent of(String type, Long taskId, String message, String stage) {
        return progress(type, taskId, message, stage, null, null, null, null, null, null);
    }

    public static PaperSseEvent progress(String type,
                                         Long taskId,
                                         String message,
                                         String stage,
                                         Integer currentSection,
                                         Integer totalSections,
                                         String sectionTitle,
                                         Integer attempt,
                                         Integer maxAttempts,
                                         Integer progressPercent) {
        return new PaperSseEvent(
                type,
                taskId,
                message,
                stage,
                Instant.now(),
                currentSection,
                totalSections,
                sectionTitle,
                attempt,
                maxAttempts,
                progressPercent
        );
    }
}
