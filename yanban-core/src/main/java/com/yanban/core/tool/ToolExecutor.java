package com.yanban.core.tool;

public interface ToolExecutor {
    ToolDefinition definition();

    /**
     * Metadata is part of the registration contract. The conservative default keeps legacy
     * executors callable by direct internal code but unavailable to governed model execution.
     */
    default ToolDescriptor descriptor() {
        return ToolDescriptorCatalog.descriptorFor(definition().name());
    }

    ToolResult execute(ToolCall call);
}
