package com.yanban.skills;

import java.nio.file.Path;
import java.util.List;

public record SkillDefinition(
        String id,
        String name,
        String description,
        boolean builtin,
        Path path,
        String prompt,
        List<String> allowedTools
) {
}
