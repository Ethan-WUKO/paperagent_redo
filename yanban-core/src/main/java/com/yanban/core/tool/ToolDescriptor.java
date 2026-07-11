package com.yanban.core.tool;

import java.util.List;

/**
 * MVP-0 governance metadata. Existing ToolDefinition remains the model-facing schema.
 */
public record ToolDescriptor(
        String name,
        String version,
        String capabilityDomain,
        List<CapabilityProfile> supportedProfiles,
        List<String> requiredPermissions,
        List<ResourceScope> resourceScopes,
        SideEffectType sideEffectType,
        ConfirmationPolicy confirmationPolicy,
        AsyncMode asyncMode,
        IdempotencyPolicy idempotencyPolicy,
        RepeatPolicy repeatPolicy,
        boolean modelVisible
) {
    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        version = version == null || version.isBlank() ? "v1" : version.trim();
        capabilityDomain = capabilityDomain == null || capabilityDomain.isBlank() ? "unspecified" : capabilityDomain.trim();
        supportedProfiles = supportedProfiles == null ? List.of() : List.copyOf(supportedProfiles);
        requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);
        resourceScopes = resourceScopes == null ? List.of() : List.copyOf(resourceScopes);
        sideEffectType = sideEffectType == null ? SideEffectType.UNKNOWN : sideEffectType;
        confirmationPolicy = confirmationPolicy == null
                ? (sideEffectType == SideEffectType.NONE ? ConfirmationPolicy.NEVER : ConfirmationPolicy.ON_SIDE_EFFECT)
                : confirmationPolicy;
        asyncMode = asyncMode == null ? AsyncMode.SYNC : asyncMode;
        idempotencyPolicy = idempotencyPolicy == null ? IdempotencyPolicy.NONE : idempotencyPolicy;
        repeatPolicy = repeatPolicy == null ? RepeatPolicy.DENY_SAME_INPUT : repeatPolicy;
    }

    /** Compatibility constructor for the initial MVP-0 descriptor shape. */
    public ToolDescriptor(String name,
                          List<String> supportedProfiles,
                          List<String> requiredPermissions,
                          SideEffectType sideEffectType,
                          ConfirmationPolicy confirmationPolicy,
                          AsyncMode asyncMode,
                          IdempotencyPolicy idempotencyPolicy,
                          RepeatPolicy repeatPolicy) {
        this(name, "v1", "unspecified",
                supportedProfiles == null ? List.of() : supportedProfiles.stream()
                        .map(CapabilityProfile::valueOf)
                        .toList(),
                requiredPermissions, List.of(), sideEffectType, confirmationPolicy, asyncMode,
                idempotencyPolicy, repeatPolicy, false);
    }

    /** Legacy executors are intentionally not model-visible until their owner supplies metadata. */
    public static ToolDescriptor legacyUnknown(String name) {
        return new ToolDescriptor(name, "legacy", "legacy", List.of(), List.of(), List.of(),
                SideEffectType.UNKNOWN, ConfirmationPolicy.NEVER, AsyncMode.SYNC,
                IdempotencyPolicy.NONE, RepeatPolicy.DENY_SAME_INPUT, false);
    }

    public enum CapabilityProfile { CHAT, PROJECT }
    public enum ResourceScope { SESSION, USER_KNOWLEDGE, PROJECT, EXTERNAL }
    public enum SideEffectType { UNKNOWN, NONE, EXTERNAL_READ, CREATE, MODIFY, DELETE, EXECUTE, EXTERNAL_SEND }
    public enum ConfirmationPolicy { NEVER, ON_SIDE_EFFECT, ON_HIGH_RISK, ALWAYS }
    public enum AsyncMode { SYNC, RUNTIME_MANAGED_ASYNC, EXTERNAL_TASK }
    public enum IdempotencyPolicy { NONE, OPTIONAL_KEY, REQUIRED_KEY }
    public enum RepeatPolicy { DENY_SAME_INPUT, ALLOW_LIMITED, POLL_UNTIL_TERMINAL }
}
