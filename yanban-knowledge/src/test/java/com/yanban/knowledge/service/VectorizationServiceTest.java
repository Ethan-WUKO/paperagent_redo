package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VectorizationServiceTest {

    @Test
    void vectorizeDocumentIndexesChunkAndStoresEsDocId() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeIndexService indexService = Mockito.mock(KnowledgeIndexService.class);
        KbChunkRepository chunkRepository = Mockito.mock(KbChunkRepository.class);
        KnowledgeElasticsearchProperties properties = new KnowledgeElasticsearchProperties();
        properties.setVectorDimensions(3);
        VectorizationService service = new VectorizationService(embeddingClient, indexService, properties, chunkRepository);

        KbDocument document = new KbDocument(1L, "paper.md", "PROCESSING", false);
        KbChunk chunk = new KbChunk(10L, 0, "alpha content");
        when(embeddingClient.embed("alpha content")).thenReturn(java.util.List.of(0.1d, 0.2d, 0.3d));
        when(indexService.indexChunk(any())).thenReturn("es-123");
        when(chunkRepository.save(any(KbChunk.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.vectorizeDocument(document, Collections.singletonList(chunk));

        assertThat(chunk.getEsDocId()).isEqualTo("es-123");
        verify(indexService).indexChunk(any());
        verify(chunkRepository).save(chunk);
    }
}
