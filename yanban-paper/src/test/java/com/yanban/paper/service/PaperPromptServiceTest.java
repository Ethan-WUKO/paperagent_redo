package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PaperPromptServiceTest {

    private final PaperPromptService promptService = new PaperPromptService();

    @Test
    void loadsAllExpectedPromptTemplates() {
        assertThat(promptService.names()).contains(
                "role-confirm",
                "research-profile",
                "section-polish",
                "section-review",
                "section-repair",
                "literature-extract",
                "gap-analysis",
                "relatedwork-gen",
                "contribution-gen",
                "abstract"
        );
    }

    @Test
    void renderReplacesVariables() {
        String rendered = promptService.render("section-review", Map.of(
                "targetLanguage", "en",
                "paperTitle", "Demo Paper",
                "researchProfile", "{problem: demo}",
                "sectionTitle", "Introduction",
                "scoreThreshold", 80,
                "diffSummary", "{}",
                "originalText", "Original section.",
                "sectionText", "This is a section."
        ));

        assertThat(rendered).contains("Demo Paper", "Introduction", "This is a section.");
        assertThat(rendered).doesNotContain("{{paperTitle}}", "{{sectionText}}");
    }

    @Test
    void renderFailsWhenRequiredVariableMissing() {
        assertThatThrownBy(() -> promptService.render("role-confirm", Map.of("targetLanguage", "zh")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paperTitle")
                .hasMessageContaining("sectionSignals");
    }

    @Test
    void unknownPromptFailsFast() {
        assertThatThrownBy(() -> promptService.render("not-exist", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown paper prompt");
    }
}
