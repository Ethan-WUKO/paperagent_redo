package com.yanban.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "^[a-zA-Z0-9_@.\\-]+$", message = "用户名只允许字母、数字、下划线、@、点和横线")
        String username,

        @NotBlank
        @Size(min = 8, max = 128)
        String password,

        String inviteCode
) {
}
