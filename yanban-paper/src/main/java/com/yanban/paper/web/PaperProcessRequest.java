package com.yanban.paper.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.multipart.MultipartFile;

public record PaperProcessRequest(
        MultipartFile mainTex,
        MultipartFile bibFile,
        MultipartFile file,
        @Min(0) Integer scoreThreshold,
        @Min(1) @Max(20) Integer maxRounds,
        @Min(1) @Max(20) Integer innerMaxAttempts,
        @Min(1) @Max(100) Integer literatureMinCount,
        @Min(1) @Max(100) Integer literatureCount,
        Boolean literatureOnly,
        @NotBlank @Pattern(regexp = "zh|en", message = "targetLanguage 仅支持 zh 或 en") String targetLanguage
) {
}
