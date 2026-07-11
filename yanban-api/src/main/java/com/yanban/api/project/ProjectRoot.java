package com.yanban.api.project;

import java.nio.file.Path;

public record ProjectRoot(Long projectId, Long userId, ProjectRootType type, Path canonicalPath) {
}
