package com.yanban.paper.web;

import com.yanban.paper.domain.PaperSection;

public record PaperSectionResponse(
        Long id,
        Long taskId,
        String sourcePath,
        Integer orderIndex,
        Integer level,
        String title,
        String role,
        Double roleConfidence,
        String roleSource,
        Integer charStart,
        Integer charEnd,
        String polishStatus,
        String reviewJson,
        String diffJson
) {
    public static PaperSectionResponse from(PaperSection section) {
        return new PaperSectionResponse(
                section.getId(),
                section.getTaskId(),
                section.getSourcePath(),
                section.getOrderIndex(),
                section.getLevel(),
                section.getTitle(),
                section.getRole(),
                section.getRoleConfidence(),
                section.getRoleSource(),
                section.getCharStart(),
                section.getCharEnd(),
                section.getPolishStatus(),
                section.getReviewJson(),
                section.getDiffJson()
        );
    }
}
