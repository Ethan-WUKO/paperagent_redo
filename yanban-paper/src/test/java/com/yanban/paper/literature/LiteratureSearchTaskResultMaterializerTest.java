package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = LiteratureSearchTaskResultMaterializerTest.TestConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class LiteratureSearchTaskResultMaterializerTest {

    private static final Long USER_ID = 11L;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {LiteratureCard.class, LiteratureSearchTask.class})
    @EnableJpaRepositories(basePackageClasses = {LiteratureCardRepository.class, LiteratureSearchTaskRepository.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private LiteratureCardRepository cards;

    @Autowired
    private LiteratureSearchTaskRepository tasks;

    @Autowired
    private ObjectMapper objectMapper;

    private LiteratureSearchTaskService taskService;
    private LiteratureCardCatalogService cardCatalogService;
    private LiteratureSearchTaskResultMaterializer materializer;

    @BeforeEach
    void setUp() {
        taskService = new LiteratureSearchTaskService(tasks, provider(null), provider(null));
        cardCatalogService = new LiteratureCardCatalogService(cards, objectMapper, new NoOpLiteratureCardIndexService());
        materializer = new LiteratureSearchTaskResultMaterializer(cardCatalogService, taskService, objectMapper);
    }

    @Test
    void materializeAndSaveCreatesCardAndBackfillsCardId() throws Exception {
        LiteratureSearchTask task = taskService.createTask(
                USER_ID,
                new LiteratureSearchTaskRequest("hybrid RAG", 8, 2020, true, "req-1", null)
        ).task();

        materializer.materializeAndSave(USER_ID, task.getId(), result(
                new AdHocLiteratureSearchService.AdHocLiteratureItem(
                        "Hybrid Retrieval for RAG",
                        List.of("A. Author"),
                        2024,
                        "Demo Journal",
                        "10.1000/rag",
                        null,
                        null,
                        "https://example.test/rag",
                        "abstract",
                        "openalex",
                        "hybrid RAG",
                        0.91,
                        "@article{rag}"
                )
        ));

        LiteratureSearchTask saved = tasks.findById(task.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_COMPLETED);
        assertThat(cards.findByDoi("10.1000/rag")).isPresent();
        JsonNode root = objectMapper.readTree(saved.getResultJson());
        JsonNode item = root.path("items").get(0);
        assertThat(item.path("cardId").asLong()).isPositive();
        assertThat(item.path("doi").asText()).isEqualTo("10.1000/rag");
    }

    @Test
    void materializeAndSaveReusesExistingCardByDoi() throws Exception {
        LiteratureCard existing = cardCatalogService.upsertCard(new LiteratureCandidate(
                "seed",
                "10.1000/rag",
                null,
                null,
                null,
                "Hybrid Retrieval for RAG",
                List.of("Seed Author"),
                2023,
                "Seed Venue",
                "seed abstract",
                "https://example.test/seed",
                null,
                null,
                List.of(),
                List.of(),
                "seed"
        ));
        LiteratureSearchTask task = taskService.createTask(
                USER_ID,
                new LiteratureSearchTaskRequest("hybrid RAG", 8, null, true, "req-2", null)
        ).task();

        materializer.materializeAndSave(USER_ID, task.getId(), result(
                new AdHocLiteratureSearchService.AdHocLiteratureItem(
                        "Hybrid Retrieval for RAG",
                        List.of("A. Author"),
                        2024,
                        "Demo Journal",
                        "https://doi.org/10.1000/rag",
                        null,
                        null,
                        "https://example.test/rag",
                        "abstract",
                        "openalex",
                        "hybrid RAG",
                        0.91,
                        "@article{rag}"
                )
        ));

        LiteratureSearchTask saved = tasks.findById(task.getId()).orElseThrow();
        JsonNode root = objectMapper.readTree(saved.getResultJson());
        assertThat(root.path("items").get(0).path("cardId").asLong()).isEqualTo(existing.getId());
        assertThat(cards.count()).isEqualTo(1);
    }

    @Test
    void materializeAndSaveReusesExistingCardByTitleHashWithoutIdentifiers() throws Exception {
        LiteratureCard existing = cardCatalogService.upsertCard(new LiteratureCandidate(
                "seed",
                null,
                null,
                null,
                null,
                "Hybrid Retrieval for RAG",
                List.of("Seed Author"),
                2023,
                "Seed Venue",
                "seed abstract",
                "https://example.test/seed",
                null,
                null,
                List.of(),
                List.of(),
                "seed"
        ));
        LiteratureSearchTask task = taskService.createTask(
                USER_ID,
                new LiteratureSearchTaskRequest("hybrid RAG", 8, null, true, "req-3", null)
        ).task();

        materializer.materializeAndSave(USER_ID, task.getId(), result(
                new AdHocLiteratureSearchService.AdHocLiteratureItem(
                        "Hybrid Retrieval For RAG",
                        List.of("A. Author"),
                        2024,
                        "Demo Journal",
                        null,
                        null,
                        null,
                        "https://example.test/rag",
                        "abstract",
                        "openalex",
                        "hybrid RAG",
                        0.91,
                        "@article{rag}"
                )
        ));

        LiteratureSearchTask saved = tasks.findById(task.getId()).orElseThrow();
        JsonNode root = objectMapper.readTree(saved.getResultJson());
        assertThat(root.path("items").get(0).path("cardId").asLong()).isEqualTo(existing.getId());
        assertThat(cards.count()).isEqualTo(1);
    }

    @Test
    void materializeAndSaveRollsBackWhenTaskAlreadyCancelling() {
        LiteratureSearchTask task = taskService.createTask(
                USER_ID,
                new LiteratureSearchTaskRequest("hybrid RAG", 8, null, true, "req-4", null)
        ).task();
        taskService.requestCancel(USER_ID, task.getId(), "user stopped");

        assertThatThrownBy(() -> materializer.materializeAndSave(USER_ID, task.getId(), result(
                new AdHocLiteratureSearchService.AdHocLiteratureItem(
                        "Hybrid Retrieval for RAG",
                        List.of("A. Author"),
                        2024,
                        "Demo Journal",
                        "10.1000/rag",
                        null,
                        null,
                        "https://example.test/rag",
                        "abstract",
                        "openalex",
                        "hybrid RAG",
                        0.91,
                        "@article{rag}"
                )
        ))).isInstanceOf(LiteratureSearchTaskResultMaterializer.LiteratureSearchTaskCancelledException.class);

        assertThat(cards.count()).isZero();
        assertThat(tasks.findById(task.getId()).orElseThrow().getResultJson()).isNull();
    }

    private AdHocLiteratureSearchService.AdHocLiteratureSearchResult result(AdHocLiteratureSearchService.AdHocLiteratureItem item) {
        return new AdHocLiteratureSearchService.AdHocLiteratureSearchResult(
                "hybrid RAG",
                List.of(item),
                2,
                1,
                1,
                List.of("openalex: timeout")
        );
    }

    private <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private final class NoOpLiteratureCardIndexService extends LiteratureCardIndexService {
        private NoOpLiteratureCardIndexService() {
            super(provider(null), new com.yanban.paper.config.PaperLiteratureProperties(), objectMapper);
        }

        @Override
        public void index(LiteratureCard card) {
        }
    }
}
