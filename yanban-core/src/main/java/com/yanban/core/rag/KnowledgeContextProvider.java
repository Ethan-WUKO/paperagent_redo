package com.yanban.core.rag;

import com.yanban.core.model.ChatMessage;
import java.util.List;

public interface KnowledgeContextProvider {
    List<KnowledgeSnippet> searchContext(String query, Long userId, int topK);

    default List<KnowledgeSnippet> searchContext(String query, Long userId, int topK, List<ChatMessage> history) {
        return searchContext(query, userId, topK);
    }
}
