package com.yanban.api.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Public request deliberately excludes ownership and access mode. */
public record CreateProjectRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 1024) String projectFolder,
        @NotEmpty List<@NotBlank String> includeRules,
        List<@NotBlank String> ignoreRules
) {
}
