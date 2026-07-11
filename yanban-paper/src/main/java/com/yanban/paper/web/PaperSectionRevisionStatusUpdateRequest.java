package com.yanban.paper.web;

import jakarta.validation.constraints.NotBlank;

public record PaperSectionRevisionStatusUpdateRequest(
        @NotBlank String status
) {
}
