package com.yanban.knowledge.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record MergeUploadRequest(
        @NotBlank String uploadId,
        @NotBlank String filename,
        @Min(1) Integer totalChunks,
        boolean isPublic,
        String mimeType
) {
}
