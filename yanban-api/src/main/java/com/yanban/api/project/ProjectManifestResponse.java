package com.yanban.api.project;

import java.util.List;

public record ProjectManifestResponse(Long projectId, String version, List<ProjectFileEntry> files) {
}
