package com.yanban.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.knowledge.service.KnowledgeSearchResult;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import org.junit.jupiter.api.Test;

class LangChain4jAdapterRagRunnerTest {

    @Test
    void contentRetrieverPreservesKnowledgeSearchMetadata() {
        KnowledgeSearchServiceContentRetriever retriever = new KnowledgeSearchServiceContentRetriever(
                (query, userId, topK) -> List.of(new KnowledgeSearchResult(
                        1003L,
                        "literature-card-graph-rag.md",
                        0,
                        "Edge-conditioned retrieval DOI 10.5555/graph-rag.2025",
                        2.5d,
                        false,
                        "u101-lit-graph-rag-001",
                        "high",
                        "knowledge_base",
                        null,
                        null
                )),
                101L,
                5
        );

        List<Content> contents = retriever.retrieve(Query.from("edge conditioned retrieval"));

        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).textSegment().metadata().getLong("documentId")).isEqualTo(1003L);
        assertThat(contents.get(0).textSegment().metadata().getString("citationId")).isEqualTo("u101-lit-graph-rag-001");
        assertThat(contents.get(0).textSegment().metadata().getString("visibility")).isEqualTo("PRIVATE");
    }

    @Test
    void adapterRunnerUsesBaselineResultFormat() {
        RagSpikeEvalCase evalCase = new RagSpikeEvalCase(
                "RAG-LC4J-006",
                "citation_metadata_preservation",
                null,
                "Which literature card supports edge-conditioned retrieval?",
                101L,
                null,
                5,
                List.of(1003L),
                List.of(2001L),
                List.of("u101-lit-graph-rag-001"),
                List.of("Edge-conditioned retrieval"),
                List.of(),
                null
        );
        LangChain4jAdapterRagRunner runner = new LangChain4jAdapterRagRunner(item -> new KnowledgeSearchServiceContentRetriever(
                (query, userId, topK) -> List.of(new KnowledgeSearchResult(
                        1003L,
                        "literature-card-graph-rag.md",
                        0,
                        "Edge-conditioned retrieval DOI 10.5555/graph-rag.2025",
                        2.5d,
                        false,
                        "u101-lit-graph-rag-001",
                        "high",
                        "knowledge_base",
                        null,
                        null
                )),
                item.userId(),
                item.topK()
        ));

        BaselineRagEvaluationResult result = runner.run(List.of(evalCase));

        assertThat(result.runner()).isEqualTo("langchain4j-adapter-only");
        assertThat(result.summary().passedCases()).isEqualTo(1);
        assertThat(result.cases().get(0).retrievedDocumentIds()).containsExactly(1003L);
        assertThat(result.cases().get(0).retrievedCitationIds()).containsExactly("u101-lit-graph-rag-001");
    }
}
