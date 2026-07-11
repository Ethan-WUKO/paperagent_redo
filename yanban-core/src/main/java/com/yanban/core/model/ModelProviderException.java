package com.yanban.core.model;

public class ModelProviderException extends RuntimeException {
    public ModelProviderException(String message) {
        super(message);
    }

    public ModelProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
