package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.domain.KbChunkRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

class HybridKnowledgeSearchServiceTest {

    @Test
    void searchUsesHybridIndexResultsWhenAvailable() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        when(embeddingClient.embed("alpha")).thenReturn(List.of(0.1d, 0.2d));
        KnowledgeSearchOptions alphaOptions = KnowledgeSearchOptions.activeOnly(1001L, 3);
        when(indexClient.search("alpha", alphaOptions, 12, List.of(0.1d, 0.2d))).thenReturn(List.of(
                new KnowledgeSearchIndexHit(1L, 0, "alpha content", 1.5d)
        ));
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(new KbDocument(1001L, "paper.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search("alpha", alphaOptions);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).filename()).isEqualTo("paper.md");
        assertThat(results.get(0).score()).isGreaterThan(1.5d);
        assertThat(results.get(0).rerankScore()).isNotNull();
        assertThat(results.get(0).rerankReason()).contains("exact_phrase");
    }

    @Test
    void searchFallsBackToDatabaseWhenHybridFails() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        when(embeddingClient.embed("beta")).thenThrow(new IllegalStateException("embedding down"));
        com.yanban.knowledge.domain.KbChunk chunk = new com.yanban.knowledge.domain.KbChunk(1L, 0, "beta keyword");
        when(chunks.searchAccessibleVersionedChunks("beta", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of(chunk));
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(new KbDocument(2002L, "notes.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search("beta", 2002L, 2);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).filename()).isEqualTo("notes.md");
    }

    @Test
    void searchUsesLookupVariantWhenNaturalQueryWrapsKey() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        String query = "find the exact answer for mentor_lookup_deepseek-20260701.";
        List<Double> vector = List.of(0.1d, 0.2d);
        KnowledgeSearchOptions options = KnowledgeSearchOptions.activeOnly(1001L, 3);
        when(embeddingClient.embed(query)).thenReturn(vector);
        when(indexClient.search(query.substring(0, query.length() - 1), options, 12, vector)).thenReturn(List.of());
        when(indexClient.search("mentor_lookup_deepseek", options, 12, vector)).thenReturn(List.of(
                new KnowledgeSearchIndexHit(1L, 0, "mentor_lookup_deepseek-20260701 key: Zhang Mingyuan.", 1.1d)
        ));
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(new KbDocument(1001L, "lab-notes.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search(query, options);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunkText()).contains("Zhang Mingyuan");
        assertThat(results.get(0).rerankReason()).contains("mentor_lookup_deepseek");
    }

    @Test
    void fallbackSearchUsesLookupVariantWhenEmbeddingFails() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        String query = "find the exact answer for beta_lookup_deepseek-20260701.";
        when(embeddingClient.embed(query)).thenThrow(new IllegalStateException("embedding down"));
        com.yanban.knowledge.domain.KbChunk chunk = new com.yanban.knowledge.domain.KbChunk(1L, 0, "beta_lookup_deepseek-20260701 key: beta keyword");
        when(chunks.searchAccessibleVersionedChunks("beta_lookup_deepseek", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of(chunk));
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(new KbDocument(2002L, "notes.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search(query, 2002L, 2);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).filename()).isEqualTo("notes.md");
        assertThat(results.get(0).rerankScore()).isNotNull();
    }

    @Test
    void fallbackSearchUsesChineseVariantsWhenEmbeddingFails() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        String query = "项目名称";
        when(embeddingClient.embed(query)).thenThrow(new IllegalStateException("embedding down"));
        com.yanban.knowledge.domain.KbChunk chunk = new com.yanban.knowledge.domain.KbChunk(5L, 0, "项目正式名称是青岚知识助手");
        when(chunks.searchAccessibleVersionedChunks("项目名称", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of());
        when(chunks.searchAccessibleVersionedChunks("项目", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of(chunk));
        when(chunks.searchAccessibleVersionedChunks("目名", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of());
        when(chunks.searchAccessibleVersionedChunks("名称", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of(chunk));
        when(documents.findById(5L)).thenReturn(java.util.Optional.of(new KbDocument(2002L, "notes.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search(query, 2002L, 2);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunkText()).contains("青岚知识助手");
        assertThat(results.get(0).rerankReason()).contains("项目");
    }

    @Test
    void hybridHydrationFiltersDeletedDocumentsAndFallsBack() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        when(embeddingClient.embed("gamma")).thenReturn(List.of(0.1d, 0.2d));
        KnowledgeSearchOptions options = KnowledgeSearchOptions.activeOnly(3003L, 2);
        when(indexClient.search("gamma", options, 8, List.of(0.1d, 0.2d))).thenReturn(List.of(
                new KnowledgeSearchIndexHit(1L, 0, "gamma deleted", 2.0d)
        ));
        KbDocument deleted = new KbDocument(3003L, "deleted.md", "READY", false);
        deleted.setVersionStatus("DELETED");
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(deleted));

        com.yanban.knowledge.domain.KbChunk activeChunk = new com.yanban.knowledge.domain.KbChunk(2L, 0, "gamma active");
        when(chunks.searchAccessibleVersionedChunks("gamma", 3003L, null, false, PageRequest.of(0, 8))).thenReturn(List.of(activeChunk));
        when(documents.findById(2L)).thenReturn(java.util.Optional.of(new KbDocument(3003L, "active.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search("gamma", options);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).filename()).isEqualTo("active.md");
        assertThat(results.get(0).versionStatus()).isEqualTo("ACTIVE");
    }
}
