package com.yanban.core.tool;

/**
 * Stable, transport-independent classification for a tool failure.
 */
public enum ToolErrorCode {
    VALIDATION_ERROR(false),
    PERMISSION_DENIED(false),
    NOT_FOUND(false),
    CONFLICT(false),
    RATE_LIMITED(true),
    TIMEOUT(true),
    TRANSIENT_EXTERNAL_ERROR(true),
    PERMANENT_EXTERNAL_ERROR(false),
    INTERNAL_ERROR(false),
    CANCELLED(false);

    private final boolean retryable;

    ToolErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
