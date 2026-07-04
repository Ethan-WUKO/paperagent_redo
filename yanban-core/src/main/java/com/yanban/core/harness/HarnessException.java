package com.yanban.core.harness;

public class HarnessException extends RuntimeException {
    public HarnessException(String message) {
        super(message);
    }

    public HarnessException(String message, Throwable cause) {
        super(message, cause);
    }
}
