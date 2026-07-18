package com.yanban.core.agent.worker;

/** Bounded failure projection with no exception, stack, host path, or credential payload. */
public record WorkerFailureInfo(String code, String message, boolean retryable)
        implements RejectsUnknownFields {
    public static final int MAX_MESSAGE_UTF8_BYTES = 4 * 1024;

    public WorkerFailureInfo {
        code = WorkerContractSupport.identifier(code, "worker failure code");
        message = WorkerContractSupport.safeText(message, "worker failure message",
                MAX_MESSAGE_UTF8_BYTES, false);
    }
}
