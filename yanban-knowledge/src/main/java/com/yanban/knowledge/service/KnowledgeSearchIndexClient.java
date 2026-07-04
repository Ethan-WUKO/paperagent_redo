package com.yanban.knowledge.service;

import java.util.List;

public interface KnowledgeSearchIndexClient {
    List<KnowledgeSearchIndexHit> search(String query, Long userId, int topK, java.util.List<Double> queryVector);
}
