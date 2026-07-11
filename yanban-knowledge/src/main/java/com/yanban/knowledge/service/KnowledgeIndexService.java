package com.yanban.knowledge.service;

public interface KnowledgeIndexService {
    String indexChunk(IndexedChunkDocument chunkDocument);

    void deleteByDocumentId(Long documentId);
}
