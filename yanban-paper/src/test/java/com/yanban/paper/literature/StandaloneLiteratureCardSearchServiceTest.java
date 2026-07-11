package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = StandaloneLiteratureCardSearchServiceTest.TestConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class StandaloneLiteratureCardSearchServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = LiteratureCard.class)
    @EnableJpaRepositories(basePackageClasses = LiteratureCardRepository.class)
    @Import(StandaloneLiteratureCardSearchService.class)
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        LiteratureCardIndexService literatureCardIndexService(ObjectMapper objectMapper) {
            return new LiteratureCardIndexService(new org.springframework.beans.factory.ObjectProvider<co.elastic.clients.elasticsearch.ElasticsearchClient>() {
                @Override
                public co.elastic.clients.elasticsearch.ElasticsearchClient getObject(Object... args) { return null; }
                @Override
                public co.elastic.clients.elasticsearch.ElasticsearchClient getIfAvailable() { return null; }
                @Override
                public co.elastic.clients.elasticsearch.ElasticsearchClient getIfUnique() { return null; }
                @Override
                public co.elastic.clients.elasticsearch.ElasticsearchClient getObject() { return null; }
            }, new com.yanban.paper.config.PaperLiteratureProperties(), objectMapper);
        }
    }

    @Autowired
    private LiteratureCardRepository cards;

    @Autowired
    private StandaloneLiteratureCardSearchService service;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void searchFindsExistingCardsByTitleKeywords() throws Exception {
        cards.save(card(
                "Hybrid Retrieval for RAG",
                2024,
                42,
                "Retrieval augmented generation with hybrid retrieval for document question answering.",
                List.of("retrieval", "rag")
        ));
        cards.save(card(
                "Unrelated paper",
                2019,
                3,
                "Different topic altogether.",
                List.of("other")
        ));

        List<LiteratureCandidate> result = service.search("hybrid retrieval rag", 5, 2020);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Hybrid Retrieval for RAG");
        assertThat(result.get(0).source()).isEqualTo("local_card");
    }

    @Test
    void searchRespectsYearFilter() throws Exception {
        cards.save(card(
                "Hybrid Retrieval for RAG",
                2018,
                42,
                "Retrieval augmented generation with hybrid retrieval for document question answering.",
                List.of("retrieval", "rag")
        ));

        List<LiteratureCandidate> result = service.search("hybrid retrieval rag", 5, 2020);

        assertThat(result).isEmpty();
    }

    private LiteratureCard card(String title, Integer year, Integer citationCount, String abstractText, List<String> fieldsOfStudy) throws Exception {
        LiteratureCard card = new LiteratureCard(hash(title), title);
        card.setPublicationYear(year);
        card.setCitationCount(citationCount);
        card.setAbstractText(abstractText);
        card.setAuthors(objectMapper.writeValueAsString(List.of("A. Author")));
        card.setFieldsOfStudyJson(objectMapper.writeValueAsString(fieldsOfStudy));
        card.setSourcesJson(objectMapper.writeValueAsString(List.of("seed")));
        return card;
    }

    private String hash(String title) {
        try {
            String normalized = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsHan}]", "");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
