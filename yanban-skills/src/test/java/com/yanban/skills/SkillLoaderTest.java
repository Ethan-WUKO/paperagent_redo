package com.yanban.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SkillLoaderTest {

    @Test
    void loadsBuiltinCodeReviewSkill() {
        SkillLoader loader = new SkillLoader();
        SkillDefinition skill = loader.loadAll().stream()
                .filter(item -> item.id().equals("code-review"))
                .findFirst()
                .orElseThrow();

        assertThat(skill.prompt()).contains("专注于代码审查");
        assertThat(skill.allowedTools()).contains("mcp_fs__read_file");
        assertThat(skill.builtin()).isTrue();
    }
}
