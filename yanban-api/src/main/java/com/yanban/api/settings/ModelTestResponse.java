package com.yanban.api.settings;

public record ModelTestResponse(
        boolean success,
        String content,
        ModelTestErrorType errorType,
        String errorMessage,
        String providerKey,
        String modelName
) {
    public static ModelTestResponse success(String content, String providerKey, String modelName) {
        return new ModelTestResponse(true, content == null ? "" : content, null, null, providerKey, modelName);
    }

    public static ModelTestResponse failure(ModelTestErrorType errorType, String errorMessage, String providerKey, String modelName) {
        return new ModelTestResponse(false, null, errorType, errorMessage, providerKey, modelName);
    }
}
