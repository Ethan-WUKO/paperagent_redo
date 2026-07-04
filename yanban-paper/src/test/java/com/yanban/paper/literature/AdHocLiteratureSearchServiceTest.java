package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdHocLiteratureSearchServiceTest {

    @Test
    void searchDeduplicatesFiltersAndBuildsBibtex() {
        LiteratureSource source = new LiteratureSource() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public List<LiteratureCandidate> search(String query, int limit) {
                return List.of(
                        new LiteratureCandidate("fake", "10.1109/demo", null, "W1", null,
                                "Polarimetric FDA-MIMO Radar for Self-Protection Jamming Suppression",
                                List.of("Alice Zhang", "Bob Li"), 2024, "IEEE TAES",
                                "This paper studies polarimetric FDA-MIMO radar and self-protection jamming suppression.",
                                "https://doi.org/10.1109/demo", null, 42, List.of(), List.of("Radar", "FDA-MIMO"), query),
                        new LiteratureCandidate("fake", "10.1109/demo", null, "W1", null,
                                "Duplicate title",
                                List.of("Someone"), 2024, "IEEE TAES", "duplicate", "https://doi.org/10.1109/demo", null, 1, List.of(), List.of(), query),
                        new LiteratureCandidate("fake", null, "2401.00001", null, null,
                                "Unrelated old work",
                                List.of("Old Author"), 2015, "arXiv", "old unrelated", "https://arxiv.org/abs/2401.00001", null, 0, List.of(), List.of(), query)
                );
            }
        };
        AdHocLiteratureSearchService service = new AdHocLiteratureSearchService(List.of(source));

        AdHocLiteratureSearchService.AdHocLiteratureSearchResult result = service.search("polarimetric FDA-MIMO self-protection jamming", 5, 2020);

        assertThat(result.rawCandidateCount()).isEqualTo(3);
        assertThat(result.uniqueCandidateCount()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).contains("Polarimetric FDA-MIMO");
        assertThat(result.items().get(0).bibtex()).contains("@article", "10.1109/demo", "Alice Zhang and Bob Li");
    }
}
