package com.yanban.knowledge.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.core.model.ChatMessage;
import com.yanban.core.rag.KnowledgeSnippet;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import com.yanban.knowledge.service.KnowledgeSearchService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeSearchContextProviderTest {

    @Test
    void searchContextUsesHistoryToExpandReferentialFollowUpQuery() {
        KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        when(searchService.search(anyString(), eq(7L), anyInt()))
                .thenAnswer(invocation -> {
                    capturedQuery.set(invocation.getArgument(0, String.class));
                    return List.of(new KnowledgeSearchResult(
                            31L,
                            "graph-rag-polished.md",
                            0,
                            "Recall@5 reached 0.80 in the polished Graph RAG run.",
                            1.8,
                            false
                    ));
                });
        KnowledgeSearchContextProvider provider = new KnowledgeSearchContextProvider(searchService);

        List<KnowledgeSnippet> snippets = provider.searchContext(
                "What about its recall?",
                7L,
                5,
                List.of(
                        ChatMessage.user("Please summarize the polished Graph RAG paper."),
                        ChatMessage.assistant("I found the polished Graph RAG paper.")
                )
        );

        assertThat(snippets).hasSize(1);
        assertThat(capturedQuery.get()).contains("recall");
        assertThat(capturedQuery.get()).contains("polished");
        assertThat(snippets.get(0).filename()).isEqualTo("graph-rag-polished.md");
        assertThat(snippets.get(0).content()).contains("Recall@5 reached 0.80");
    }
}
