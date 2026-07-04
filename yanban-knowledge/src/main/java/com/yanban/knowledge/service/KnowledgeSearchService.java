package com.yanban.knowledge.service;

import java.util.List;

public interface KnowledgeSearchService {
    List<KnowledgeSearchResult> search(String query, Long userId, int topK);
}
