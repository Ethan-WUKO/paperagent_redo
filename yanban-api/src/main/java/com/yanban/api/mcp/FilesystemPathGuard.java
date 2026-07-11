package com.yanban.api.mcp;

import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FilesystemPathGuard {

    public void validateAllowed(String path, List<String> roots) {
        if (path == null || path.isBlank()) {
            return;
        }
        Path normalized = Path.of(path).normalize().toAbsolutePath();
        boolean allowed = roots.stream()
                .map(root -> Path.of(root).normalize().toAbsolutePath())
                .anyMatch(normalized::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("路径不在允许的 filesystem roots 中: " + path);
        }
    }
}
