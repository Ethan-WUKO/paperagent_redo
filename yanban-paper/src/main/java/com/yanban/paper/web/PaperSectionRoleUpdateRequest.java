package com.yanban.paper.web;

import jakarta.validation.constraints.NotBlank;

public record PaperSectionRoleUpdateRequest(
        @NotBlank String role
) {
}
