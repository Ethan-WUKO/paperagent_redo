package com.yanban.api.user;

public record UserMeResponse(Long id, String username, String accountType, boolean demo) {
}
