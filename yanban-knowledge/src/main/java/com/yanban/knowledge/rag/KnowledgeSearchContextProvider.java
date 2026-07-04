package com.yanban.knowledge.rag;

import com.yanban.core.rag.KnowledgeContextProvider;
import com.yanban.core.rag.KnowledgeSnippet;
import com.yanban.knowledge.service.KnowledgeSearchService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeSearchContextProvider implements KnowledgeContextProvider {

    private final KnowledgeSearchService searchService;

    public KnowledgeSearchContextProvider(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public List<KnowledgeSnippet> searchContext(String query, Long userId, int topK) {
        return searchService.search(query, userId, topK).stream()
                .map(result -> new KnowledgeSnippet(
                        result.documentId(),
                        result.filename(),
                        result.chunkIndex(),
                        result.chunkText(),
                        result.score(),
                        result.citationId(),
                        result.scoreBand(),
                        result.source(),
                        result.rerankScore(),
                        result.rerankReason()
                ))
                .toList();
    }
}
