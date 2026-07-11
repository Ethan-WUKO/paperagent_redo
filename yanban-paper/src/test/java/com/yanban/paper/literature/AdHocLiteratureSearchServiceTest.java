package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdHocLiteratureSearchServiceTest {

    private LiteratureSource source;
    private StandaloneLiteratureCardSearchService localCardSearchService;
    private AdHocLiteratureSearchService service;

    @BeforeEach
    void setUp() {
        source = mock(LiteratureSource.class);
        localCardSearchService = mock(StandaloneLiteratureCardSearchService.class);
        when(source.name()).thenReturn("openalex");
        service = new AdHocLiteratureSearchService(List.of(source), localCardSearchService);
    }

    @Test
    void searchIncludesLocalCardMatchesWhenExternalResultsAreEmpty() {
        when(localCardSearchService.search("hybrid RAG", 8, 2020)).thenReturn(List.of(localCandidate()));
        when(source.search("hybrid RAG", 16)).thenReturn(List.of());

        AdHocLiteratureSearchService.AdHocLiteratureSearchResult result = service.search("hybrid RAG", 8, 2020);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).isEqualTo("Hybrid Retrieval for RAG");
        assertThat(result.items().get(0).source()).isEqualTo("local_card");
        verify(source).search("hybrid RAG", 16);
    }

    @Test
    void searchFallsBackToExternalWhenNoLocalMatchesExist() {
        when(localCardSearchService.search("hybrid RAG", 8, null)).thenReturn(List.of());
        when(source.search("hybrid RAG", 16)).thenReturn(List.of(externalCandidate()));

        AdHocLiteratureSearchService.AdHocLiteratureSearchResult result = service.search("hybrid RAG", 8, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).source()).isEqualTo("openalex");
        assertThat(result.rawCandidateCount()).isEqualTo(1);
    }

    @Test
    void searchDeduplicatesLocalAndExternalHitsByIdentity() {
        when(localCardSearchService.search("hybrid RAG", 8, null)).thenReturn(List.of(localCandidate()));
        when(source.search("hybrid RAG", 16)).thenReturn(List.of(externalCandidate()));

        AdHocLiteratureSearchService.AdHocLiteratureSearchResult result = service.search("hybrid RAG", 8, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.uniqueCandidateCount()).isEqualTo(1);
        assertThat(result.rawCandidateCount()).isEqualTo(2);
        assertThat(result.items().get(0).source()).isEqualTo("local_card");
        assertThat(result.items().get(0).duplicateStatus()).isEqualTo("MERGED_DUPLICATES");
        assertThat(result.items().get(0).duplicateSources()).containsExactly("local_card", "openalex");
        assertThat(result.items().get(0).duplicateMergeCount()).isEqualTo(1);
        assertThat(result.items().get(0).citationStatus()).isEqualTo("BIBTEX_READY_VERIFY_BEFORE_SUBMISSION");
        assertThat(result.items().get(0).metadataRiskLevel()).isEqualTo("LOW");
        assertThat(result.items().get(0).metadataRiskNotes()).isEmpty();
        assertThat(result.items().get(0).rankingBasis()).anyMatch(value -> value.startsWith("score="));
        assertThat(result.items().get(0).deduplicationKey()).isEqualTo("doi:10.1000/rag");
    }

    @Test
    void searchMarksMergedDuplicatesWhenSameSourceReturnsDuplicatePapers() {
        when(localCardSearchService.search("hybrid RAG", 8, null)).thenReturn(List.of());
        when(source.search("hybrid RAG", 16)).thenReturn(List.of(externalCandidate(), externalCandidate()));

        AdHocLiteratureSearchService.AdHocLiteratureSearchResult result = service.search("hybrid RAG", 8, null);

        assertThat(result.rawCandidateCount()).isEqualTo(2);
        assertThat(result.uniqueCandidateCount()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).duplicateSources()).containsExactly("openalex");
        assertThat(result.items().get(0).duplicateMergeCount()).isEqualTo(1);
        assertThat(result.items().get(0).duplicateStatus()).isEqualTo("MERGED_DUPLICATES");
    }

    @Test
    void searchMarksMetadataRiskForIncompleteCitationMetadata() {
        when(localCardSearchService.search("hybrid RAG", 8, null)).thenReturn(List.of());
        when(source.search("hybrid RAG", 16)).thenReturn(List.of(incompleteCandidate()));

        AdHocLiteratureSearchService.AdHocLiteratureSearchResult result = service.search("hybrid RAG", 8, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).citationStatus()).isEqualTo("BIBTEX_NEEDS_METADATA_REVIEW");
        assertThat(result.items().get(0).metadataRiskLevel()).isEqualTo("HIGH");
        assertThat(result.items().get(0).metadataRiskNotes()).contains("missing authors", "missing publication year", "missing stable identifier or URL");
    }

    private LiteratureCandidate localCandidate() {
        return new LiteratureCandidate(
                "local_card",
                "10.1000/rag",
                null,
                null,
                null,
                "Hybrid Retrieval for RAG",
                List.of("A. Author"),
                2024,
                "Demo Journal",
                "cached abstract",
                "https://example.test/rag",
                null,
                42,
                List.of(),
                List.of("retrieval"),
                "hybrid RAG"
        );
    }

    private LiteratureCandidate externalCandidate() {
        return new LiteratureCandidate(
                "openalex",
                "https://doi.org/10.1000/rag",
                null,
                null,
                null,
                "Hybrid Retrieval for RAG",
                List.of("B. Author"),
                2024,
                "Demo Journal",
                "fresh abstract",
                "https://example.test/rag",
                null,
                7,
                List.of(),
                List.of("retrieval"),
                "hybrid RAG"
        );
    }

    private LiteratureCandidate incompleteCandidate() {
        return new LiteratureCandidate(
                "openalex",
                null,
                null,
                null,
                null,
                "Hybrid RAG Metadata Sparse Candidate",
                List.of(),
                null,
                null,
                "sparse metadata candidate about hybrid RAG",
                null,
                null,
                1,
                List.of(),
                List.of("retrieval"),
                "hybrid RAG"
        );
    }
}
