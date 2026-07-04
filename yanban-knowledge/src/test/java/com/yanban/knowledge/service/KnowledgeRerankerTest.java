package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeRerankerTest {

    private final KnowledgeReranker reranker = new KnowledgeReranker();

    @Test
    void rerankPromotesExactPhraseAndAddsExplanation() {
        KnowledgeSearchResult weakVectorExactMatch = new KnowledgeSearchResult(
                1L,
                "rag-notes.md",
                1,
                "rag_quality_metric_lookup: citation precision is required.",
                0.9d,
                false
        );
        KnowledgeSearchResult strongVectorLooseMatch = new KnowledgeSearchResult(
                2L,
                "other.md",
                0,
                "general evaluation metric notes without the exact lookup phrase",
                1.7d,
                false
        );

        List<KnowledgeSearchResult> results = reranker.rerank(
                "rag_quality_metric_lookup",
                List.of(strongVectorLooseMatch, weakVectorExactMatch),
                2
        );

        assertThat(results).hasSize(2);
        assertThat(results.get(0).documentId()).isEqualTo(1L);
        assertThat(results.get(0).rerankScore()).isGreaterThan(results.get(1).rerankScore());
        assertThat(results.get(0).rerankReason()).contains("exact_phrase");
        assertThat(results.get(0).scoreBand()).isEqualTo("high");
    }

    @Test
    void rerankDeduplicatesDocumentChunkPairs() {
        KnowledgeSearchResult lower = new KnowledgeSearchResult(1L, "a.md", 0, "alpha", 1.0d, false);
        KnowledgeSearchResult higher = new KnowledgeSearchResult(1L, "a.md", 0, "alpha alpha", 2.0d, false);

        List<KnowledgeSearchResult> results = reranker.rerank("alpha", List.of(lower, higher), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(2.0d);
    }

    @Test
    void queryVariantsExtractLookupTokensFromNaturalQuestions() {
        List<String> variants = KnowledgeQueryVariants.expand(
                "Find the exact answer for mentor_lookup_deepseek-20260701."
        );

        assertThat(variants)
                .contains("find the exact answer for mentor_lookup_deepseek-20260701")
                .contains("mentor_lookup_deepseek")
                .contains("20260701");
    }

    @Test
    void rerankPromotesLookupTokenCitationOverHigherVectorNoise() {
        KnowledgeSearchResult correctCitation = new KnowledgeSearchResult(
                1L,
                "yanban-lab-notes.pdf",
                0,
                "mentor_lookup_deepseek-20260701 key: Zhang Mingyuan.",
                1.0d,
                false
        );
        KnowledgeSearchResult noisyHighVector = new KnowledgeSearchResult(
                2L,
                "research-plan.docx",
                0,
                "20260701 general notes without the mentor lookup key.",
                9.0d,
                false
        );

        List<KnowledgeSearchResult> results = reranker.rerank(
                "Find the exact answer for mentor_lookup_deepseek-20260701.",
                List.of(noisyHighVector, correctCitation),
                2
        );

        assertThat(results.get(0).documentId()).isEqualTo(1L);
        assertThat(results.get(0).rerankReason()).contains("lookup_token_match");
    }
}
