package com.yanban.core.rag;

import java.util.List;

public interface KnowledgeContextProvider {
    List<KnowledgeSnippet> searchContext(String query, Long userId, int topK);
}
