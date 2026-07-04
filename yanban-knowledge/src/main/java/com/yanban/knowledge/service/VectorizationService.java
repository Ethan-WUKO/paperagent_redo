package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VectorizationService {

    private final EmbeddingClient embeddingClient;
    private final KnowledgeIndexService knowledgeIndexService;
    private final KnowledgeElasticsearchProperties elasticsearchProperties;
    private final KbChunkRepository chunks;

    public VectorizationService(EmbeddingClient embeddingClient,
                                KnowledgeIndexService knowledgeIndexService,
                                KnowledgeElasticsearchProperties elasticsearchProperties,
                                KbChunkRepository chunks) {
        this.embeddingClient = embeddingClient;
        this.knowledgeIndexService = knowledgeIndexService;
        this.elasticsearchProperties = elasticsearchProperties;
        this.chunks = chunks;
    }

    @Transactional
    public void vectorizeDocument(KbDocument document, List<KbChunk> documentChunks) {
        for (KbChunk chunk : documentChunks) {
            List<Double> vector = embeddingClient.embed(chunk.getChunkText());
            validateDimensions(vector);
            String esDocId = knowledgeIndexService.indexChunk(new IndexedChunkDocument(
                    chunk.getId(),
                    document.getId(),
                    document.getUserId(),
                    Boolean.TRUE.equals(document.getIsPublic()),
                    chunk.getChunkIndex(),
                    chunk.getChunkText(),
                    vector
            ));
            chunk.setEsDocId(esDocId);
            chunks.save(chunk);
        }
    }

    private void validateDimensions(List<Double> vector) {
        if (vector == null || vector.size() != elasticsearchProperties.getVectorDimensions()) {
            throw new IllegalStateException("Embedding 维度与 Elasticsearch 配置不一致");
        }
    }
}
