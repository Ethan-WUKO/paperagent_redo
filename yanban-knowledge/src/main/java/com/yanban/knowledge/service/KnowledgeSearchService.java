package com.yanban.knowledge.service;

import java.util.List;

public interface KnowledgeSearchService {
    List<KnowledgeSearchResult> search(String query, Long userId, int topK);

    default List<KnowledgeSearchResult> search(String query, KnowledgeSearchOptions options) {
        if (options == null) {
            return List.of();
        }
        return search(query, options.userId(), options.topK());
    }
}
