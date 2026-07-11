package com.yanban.knowledge.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.multipart.MultipartFile;

public record ChunkUploadRequest(
        @NotBlank String uploadId,
        @NotBlank String filename,
        @Min(0) Integer chunkNumber,
        @Min(1) Integer totalChunks,
        String chunkMd5,
        MultipartFile file
) {
}
