package com.yanban.api.mcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilesystemPathGuardTest {

    private final FilesystemPathGuard guard = new FilesystemPathGuard();
    private final String root = Path.of("workspace").toAbsolutePath().normalize().toString();

    @Test
    void allowsFileInsideRoot() {
        assertThatCode(() -> guard.validateAllowed(Path.of(root, "paper.txt").toString(), List.of(root)))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsNestedNormalizedPathInsideRoot() {
        assertThatCode(() -> guard.validateAllowed(Path.of(root, "sub", "..", "notes.md").toString(), List.of(root)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPathOutsideRoot() {
        assertThatThrownBy(() -> guard.validateAllowed(Path.of(root).getParent().resolve("other.txt").toString(), List.of(root)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTraversalOutsideRoot() {
        assertThatThrownBy(() -> guard.validateAllowed(Path.of(root, "..", "..", "escape.txt").toString(), List.of(root)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
