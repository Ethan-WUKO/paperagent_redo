package com.yanban.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ToolResult(
        String toolCallId,
        String toolName,
        boolean success,
        JsonNode output,
        ToolErrorCode errorCode,
        String errorMessage,
        boolean retryable,
        List<String> evidenceRefs,
        List<String> artifactRefs,
        List<String> sideEffects,
        String version
) {
    public ToolResult {
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
        sideEffects = sideEffects == null ? List.of() : List.copyOf(sideEffects);
        retryable = !success && (retryable || (errorCode != null && errorCode.retryable()));
    }

    /** Compatibility constructor for existing registered tools. */
    public ToolResult(String toolCallId, String toolName, boolean success, JsonNode output, String errorMessage) {
        this(toolCallId, toolName, success, output,
                success ? null : ToolErrorCode.INTERNAL_ERROR,
                errorMessage, false, List.of(), List.of(), List.of(), "v1");
    }

    public static ToolResult success(String toolCallId, String toolName, JsonNode output) {
        return new ToolResult(toolCallId, toolName, true, output, null, null,
                false, List.of(), List.of(), List.of(), "v1");
    }

    public static ToolResult failure(String toolCallId, String toolName, String errorMessage) {
        return failure(toolCallId, toolName, ToolErrorCode.INTERNAL_ERROR, errorMessage);
    }

    public static ToolResult failure(String toolCallId, String toolName,
                                     ToolErrorCode errorCode, String errorMessage) {
        return new ToolResult(toolCallId, toolName, false, null, errorCode, errorMessage,
                errorCode != null && errorCode.retryable(), List.of(), List.of(), List.of(), "v1");
    }
}
