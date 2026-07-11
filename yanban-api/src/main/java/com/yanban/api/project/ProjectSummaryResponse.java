package com.yanban.api.project;

import java.time.Instant;

public record ProjectSummaryResponse(Long id, String name, ProjectAccessMode accessMode, Instant createdAt) {

    static ProjectSummaryResponse from(Project project) {
        return new ProjectSummaryResponse(project.getId(), project.getName(), project.getAccessMode(), project.getCreatedAt());
    }
}
