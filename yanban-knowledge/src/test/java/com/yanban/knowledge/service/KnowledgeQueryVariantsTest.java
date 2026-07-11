package com.yanban.knowledge.service;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeQueryVariantsTest {

    @Test
    void expandPreservesChinesePhraseAndAddsUsefulBigrams() {
        List<String> variants = KnowledgeQueryVariants.expand("项目名称");

        assertThat(variants)
                .contains("项目名称")
                .contains("项目")
                .contains("名称");
    }

    @Test
    void expandKeepsAsciiLookupTokens() {
        List<String> variants = KnowledgeQueryVariants.expand("Please find kb_orchid_314159");

        assertThat(variants).contains("kb_orchid_314159");
    }
}
