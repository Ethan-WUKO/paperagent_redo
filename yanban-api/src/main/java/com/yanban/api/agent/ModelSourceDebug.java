package com.yanban.api.agent;

public record ModelSourceDebug(
        String providerKey,
        String modelName,
        String sourceType,
        String sourceLabel
) {
}
