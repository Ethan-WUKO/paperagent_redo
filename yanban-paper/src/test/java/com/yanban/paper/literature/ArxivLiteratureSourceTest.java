package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ArxivLiteratureSourceTest {

    @Test
    void parseAtomEntries() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2401.12345v1</id>
                    <published>2024-01-01T00:00:00Z</published>
                    <title> A Demo Paper </title>
                    <summary> This is an abstract. </summary>
                    <author><name>Alice</name></author>
                    <category term="cs.CL" />
                    <link title="pdf" href="http://arxiv.org/pdf/2401.12345v1" type="application/pdf" />
                  </entry>
                </feed>
                """;

        List<LiteratureCandidate> candidates = new ArxivLiteratureSource(RestClient.builder().build()).parse(xml, "demo");

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.source()).isEqualTo("arxiv");
            assertThat(candidate.arxivId()).isEqualTo("2401.12345v1");
            assertThat(candidate.title()).isEqualTo("A Demo Paper");
            assertThat(candidate.authors()).containsExactly("Alice");
            assertThat(candidate.year()).isEqualTo(2024);
            assertThat(candidate.fieldsOfStudy()).containsExactly("cs.CL");
            assertThat(candidate.pdfUrl()).contains("2401.12345v1");
        });
    }
}
