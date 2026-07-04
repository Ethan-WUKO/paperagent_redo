package com.yanban.api.settings;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record UserSettingsRequest(
        @Size(max = 64) String defaultProvider,
        @Size(max = 255) String deepseekApiKey,
        @Size(max = 255) String glmApiKey,
        @Size(max = 255) String githubPat,
        @Size(max = 128) String deepseekModel,
        @Size(max = 128) String glmModel,
        List<@Size(max = 128) String> deepseekModels,
        List<@Size(max = 128) String> glmModels,
        @DecimalMin("0.00") @DecimalMax("2.00") BigDecimal deepseekTemperature,
        @Min(1) @Max(100) Integer maxSteps,
        Boolean ragDefaultEnabled,
        List<@Size(max = 512) String> filesystemRoots,
        List<@Size(max = 128) String> disabledSkills
) {
}
