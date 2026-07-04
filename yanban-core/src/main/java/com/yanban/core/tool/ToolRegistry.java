package com.yanban.core.tool;

import com.yanban.core.model.ToolSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ToolRegistry {

    private final Map<String, ToolExecutor> executors = new LinkedHashMap<>();

    public ToolRegistry register(ToolExecutor executor) {
        String name = executor.definition().name();
        if (executors.containsKey(name)) {
            throw new DuplicateToolException(name);
        }
        executors.put(name, executor);
        return this;
    }

    public Optional<ToolExecutor> find(String name) {
        return Optional.ofNullable(executors.get(name));
    }

    public ToolResult execute(ToolCall call) {
        ToolExecutor executor = executors.get(call.name());
        if (executor == null) {
            throw new ToolNotFoundException(call.name());
        }
        return executor.execute(call);
    }

    public ToolResult execute(ToolCall call, Set<String> allowedToolNames) {
        if (allowedToolNames != null && !allowedToolNames.contains(call.name())) {
            throw new ToolNotFoundException(call.name());
        }
        return execute(call);
    }

    public Set<String> listToolNames() {
        return Set.copyOf(executors.keySet());
    }

    public List<ToolDefinition> listDefinitions() {
        return Collections.unmodifiableList(executors.values().stream()
                .map(ToolExecutor::definition)
                .toList());
    }

    public List<ToolSpec> listToolsForModel() {
        return listToolsForModel(null);
    }

    public List<ToolSpec> listToolsForModel(Set<String> allowedToolNames) {
        List<ToolSpec> tools = new ArrayList<>();
        for (ToolExecutor executor : executors.values()) {
            String name = executor.definition().name();
            if (allowedToolNames == null || allowedToolNames.contains(name)) {
                tools.add(executor.definition().toModelToolSpec());
            }
        }
        return Collections.unmodifiableList(tools);
    }
}
