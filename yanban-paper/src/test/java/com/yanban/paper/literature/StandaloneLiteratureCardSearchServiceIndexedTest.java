package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class StandaloneLiteratureCardSearchServiceIndexedTest {

    private LiteratureCardRepository cards;
    private LiteratureCardIndexService indexService;
    private StandaloneLiteratureCardSearchService service;

    @BeforeEach
    void setUp() {
        cards = mock(LiteratureCardRepository.class);
        indexService = mock(LiteratureCardIndexService.class);
        service = new StandaloneLiteratureCardSearchService(cards, new ObjectMapper(), indexService);
    }

    @Test
    void searchUsesIndexedIdsBeforeDbFallback() {
        LiteratureCard indexed = card(88L, "Hybrid Retrieval for RAG", 2024, 42);
        when(indexService.searchCardIds("hybrid retrieval rag", 5)).thenReturn(List.of(88L));
        when(cards.findAllById(List.of(88L))).thenReturn(List.of(indexed));

        List<LiteratureCandidate> result = service.search("hybrid retrieval rag", 5, 2020);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Hybrid Retrieval for RAG");
        verify(cards, never()).searchByKeyword("hybrid", PageRequest.of(0, 5));
    }

    @Test
    void searchFallsBackToDbWhenIndexHasNoHits() {
        LiteratureCard dbHit = card(99L, "Hybrid Retrieval for RAG", 2024, 42);
        when(indexService.searchCardIds("hybrid retrieval rag", 5)).thenReturn(List.of());
        when(cards.searchByKeyword("hybrid", PageRequest.of(0, 5))).thenReturn(List.of(dbHit));
        when(cards.searchByKeyword("retrieval", PageRequest.of(0, 5))).thenReturn(List.of(dbHit));
        when(cards.searchByKeyword("rag", PageRequest.of(0, 5))).thenReturn(List.of(dbHit));

        List<LiteratureCandidate> result = service.search("hybrid retrieval rag", 5, 2020);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Hybrid Retrieval for RAG");
        verify(cards).searchByKeyword("hybrid", PageRequest.of(0, 5));
    }

    private LiteratureCard card(Long id, String title, Integer year, Integer citationCount) {
        LiteratureCard card = new LiteratureCard("hash", title);
        ReflectionTestUtils.setField(card, "id", id);
        card.setPublicationYear(year);
        card.setCitationCount(citationCount);
        card.setAuthors("[\"A. Author\"]");
        card.setFieldsOfStudyJson("[\"retrieval\"]");
        card.setSourcesJson("[\"seed\"]");
        card.setAbstractText("abstract");
        return card;
    }
}
