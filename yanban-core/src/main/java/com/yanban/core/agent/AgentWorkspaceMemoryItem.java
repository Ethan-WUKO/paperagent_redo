package com.yanban.core.agent;

public record AgentWorkspaceMemoryItem(AgentWorkspaceMemoryType type, String reference, String content) {
    public AgentWorkspaceMemoryItem {
        if (type == null || content == null || content.isBlank()) {
            throw new IllegalArgumentException("workspace memory requires an auditable type and content");
        }
        reference = reference == null || reference.isBlank() ? null : reference.trim();
        content = content.trim();
    }

    public String deduplicationKey() {
        return type + ":" + (reference == null ? content : reference);
    }
}
