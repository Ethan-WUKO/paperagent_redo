package com.yanban.api.auth;

public record AuthResponse(String tokenType, String accessToken, String refreshToken, long expiresInSeconds) {
    public static AuthResponse bearer(String accessToken, String refreshToken, long expiresInSeconds) {
        return new AuthResponse("Bearer", accessToken, refreshToken, expiresInSeconds);
    }
}
