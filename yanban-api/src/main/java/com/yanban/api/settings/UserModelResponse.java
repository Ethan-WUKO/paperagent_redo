package com.yanban.api.settings;

import java.time.Instant;

public record UserModelResponse(
        Long id,
        String providerKey,
        String label,
        String modelName,
        String apiUrl,
        boolean apiKeyConfigured,
        boolean builtin,
        Integer sortOrder,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserModelResponse from(UserModel model) {
        return new UserModelResponse(
                model.getId(),
                model.getProviderKey(),
                model.getProviderLabel(),
                model.getModelName(),
                model.getApiUrl(),
                model.getApiKeyEncrypted() != null && !model.getApiKeyEncrypted().isBlank(),
                Boolean.TRUE.equals(model.getBuiltin()),
                model.getSortOrder(),
                model.getCreatedAt(),
                model.getUpdatedAt()
        );
    }
}
