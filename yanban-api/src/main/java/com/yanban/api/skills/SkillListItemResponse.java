package com.yanban.api.skills;

public record SkillListItemResponse(
        String id,
        String name,
        String description,
        boolean builtin,
        boolean enabled,
        String path
) {
}
