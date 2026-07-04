package com.yanban.paper.web;

import jakarta.validation.constraints.NotBlank;

public record PaperSuggestionStatusUpdateRequest(
        @NotBlank String status
) {
}
