package com.yanban.api.skills;

import java.util.Set;

public record ResolvedSkill(
        String id,
        String prompt,
        Set<String> allowedTools
) {
}
