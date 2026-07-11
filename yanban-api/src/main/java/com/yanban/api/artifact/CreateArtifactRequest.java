package com.yanban.api.artifact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateArtifactRequest(
        Long sessionId,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 64) String artifactType,
        @NotBlank String content,
        @Size(max = 64) String sourceType,
        List<ArtifactSourceRef> sourceRefs
) {
}
