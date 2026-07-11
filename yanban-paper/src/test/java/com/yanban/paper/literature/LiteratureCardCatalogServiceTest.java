package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LiteratureCardCatalogServiceTest {

    private LiteratureCardRepository cards;
    private LiteratureCardIndexService indexService;
    private LiteratureCardCatalogService service;

    @BeforeEach
    void setUp() {
        cards = mock(LiteratureCardRepository.class);
        indexService = mock(LiteratureCardIndexService.class);
        service = new LiteratureCardCatalogService(cards, new ObjectMapper(), indexService);
    }

    @Test
    void upsertCardIndexesNewlyCreatedCard() {
        when(cards.findByDoi("10.1000/rag")).thenReturn(Optional.empty());
        when(cards.findFirstByTitleHash(any())).thenReturn(Optional.empty());
        when(cards.save(any(LiteratureCard.class))).thenAnswer(invocation -> {
            LiteratureCard saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 101L);
            return saved;
        });

        LiteratureCard saved = service.upsertCard(candidate("10.1000/rag"));

        assertThat(saved.getId()).isEqualTo(101L);
        verify(indexService).index(saved);
    }

    @Test
    void upsertCardIndexesReusedCardAfterRefresh() {
        LiteratureCard existing = new LiteratureCard("hash", "Hybrid Retrieval for RAG");
        ReflectionTestUtils.setField(existing, "id", 88L);
        when(cards.findByDoi("10.1000/rag")).thenReturn(Optional.of(existing));
        when(cards.save(existing)).thenReturn(existing);

        LiteratureCard saved = service.upsertCard(candidate("10.1000/rag"));

        assertThat(saved.getId()).isEqualTo(88L);
        verify(indexService).index(existing);
    }

    private LiteratureCandidate candidate(String doi) {
        return new LiteratureCandidate(
                "openalex",
                doi,
                null,
                null,
                null,
                "Hybrid Retrieval for RAG",
                List.of("A. Author"),
                2024,
                "Demo Journal",
                "abstract",
                "https://example.test/rag",
                null,
                42,
                List.of(),
                List.of("retrieval"),
                "hybrid RAG"
        );
    }
}
