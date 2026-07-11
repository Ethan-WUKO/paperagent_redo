package com.yanban.knowledge.service;

import java.util.List;

public interface KnowledgeSearchIndexClient {
    List<KnowledgeSearchIndexHit> search(String query, KnowledgeSearchOptions options, int topK, java.util.List<Double> queryVector);
}
