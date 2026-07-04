package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAlexLiteratureSourceTest {

    @Test
    void parsesOpenAlexWorksResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openalex.org");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.openalex.org/works?search=rag&per-page=5"))
                .andRespond(withSuccess("""
                        {
                          "results": [
                            {
                              "id": "https://openalex.org/W1",
                              "doi": "https://doi.org/10.1000/rag",
                              "title": "Retrieval Augmented Generation",
                              "publication_year": 2023,
                              "cited_by_count": 12,
                              "authorships": [{"author": {"display_name": "Alice"}}],
                              "primary_location": {"source": {"display_name": "DemoConf"}, "pdf_url": "https://example.org/paper.pdf"},
                              "abstract_inverted_index": {"Retrieval": [0], "generation": [2], "augmented": [1]},
                              "referenced_works": ["https://openalex.org/W0"],
                              "concepts": [{"display_name": "Information retrieval"}]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<LiteratureCandidate> candidates = new OpenAlexLiteratureSource(builder.build()).search("rag", 5);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.source()).isEqualTo("openalex");
            assertThat(candidate.doi()).isEqualTo("https://doi.org/10.1000/rag");
            assertThat(candidate.openAlexId()).isEqualTo("https://openalex.org/W1");
            assertThat(candidate.authors()).containsExactly("Alice");
            assertThat(candidate.abstractText()).isEqualTo("Retrieval augmented generation");
            assertThat(candidate.fieldsOfStudy()).containsExactly("Information retrieval");
        });
        server.verify();
    }
}
