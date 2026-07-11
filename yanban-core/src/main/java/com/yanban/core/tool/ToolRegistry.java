package com.yanban.core.tool;

import com.yanban.core.model.ToolSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

public class ToolRegistry {

    private final Map<String, RegisteredTool> executors = new LinkedHashMap<>();

    public ToolRegistry register(ToolExecutor executor) {
        String name = executor.definition().name();
        ToolDescriptor descriptor = executor.descriptor();
        if (!name.equals(descriptor.name())) {
            throw new IllegalArgumentException("tool descriptor name must match definition name: " + name);
        }
        if (executors.containsKey(name)) {
            throw new DuplicateToolException(name);
        }
        executors.put(name, new RegisteredTool(executor, descriptor));
        return this;
    }

    public Optional<ToolExecutor> find(String name) {
        return Optional.ofNullable(executors.get(name)).map(RegisteredTool::executor);
    }

    public Optional<ToolDescriptor> findDescriptor(String name) {
        return Optional.ofNullable(executors.get(name)).map(RegisteredTool::descriptor);
    }

    public ToolResult execute(ToolCall call) {
        RegisteredTool executor = executors.get(call.name());
        if (executor == null) {
            throw new ToolNotFoundException(call.name());
        }
        return executor.executor().execute(call);
    }

    public ToolResult execute(ToolCall call, Set<String> allowedToolNames) {
        RegisteredTool registered = executors.get(call.name());
        if (allowedToolNames == null || registered == null || !allowedToolNames.contains(call.name())
                || !isGovernedExposable(registered.descriptor())) {
            throw new ToolNotFoundException(call.name());
        }
        return registered.executor().execute(call);
    }

    public Set<String> listToolNames() {
        return Set.copyOf(executors.keySet());
    }

    public List<ToolDefinition> listDefinitions() {
        return Collections.unmodifiableList(executors.values().stream()
                .map(registered -> registered.executor().definition())
                .toList());
    }

    public List<ToolDescriptor> listDescriptors() {
        return Collections.unmodifiableList(executors.values().stream()
                .map(RegisteredTool::descriptor)
                .toList());
    }

    public List<ToolSpec> listToolsForModel() {
        return List.of();
    }

    public List<ToolSpec> listToolsForModel(Set<String> allowedToolNames) {
        Objects.requireNonNull(allowedToolNames, "allowedToolNames must be resolved before model exposure");
        List<ToolSpec> tools = new ArrayList<>();
        for (RegisteredTool registered : executors.values()) {
            ToolExecutor executor = registered.executor();
            String name = executor.definition().name();
            ToolDescriptor descriptor = registered.descriptor();
            if (allowedToolNames.contains(name) && isGovernedExposable(descriptor)) {
                tools.add(executor.definition().toModelToolSpec());
            }
        }
        return Collections.unmodifiableList(tools);
    }

    private record RegisteredTool(ToolExecutor executor, ToolDescriptor descriptor) {
    }

    private boolean isGovernedExposable(ToolDescriptor descriptor) {
        return descriptor.modelVisible()
                && descriptor.sideEffectType() != ToolDescriptor.SideEffectType.UNKNOWN
                && descriptor.confirmationPolicy() == ToolDescriptor.ConfirmationPolicy.NEVER;
    }
}
