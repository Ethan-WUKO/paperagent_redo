package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.PaperTaskLiterature;
import com.yanban.paper.domain.PaperTaskLiteratureRepository;
import com.yanban.paper.service.PaperStorageService;
import com.yanban.paper.service.ResearchProfileResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
@ContextConfiguration(classes = LiteratureServiceTest.TestConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class LiteratureServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {LiteratureCard.class, PaperTask.class, PaperTaskLiterature.class, PaperTaskAnalysis.class, PaperTaskArtifact.class})
    @EnableJpaRepositories(basePackageClasses = {LiteratureCardRepository.class, PaperTaskRepository.class, PaperTaskLiteratureRepository.class, PaperTaskAnalysisRepository.class, PaperTaskArtifactRepository.class})
    @Import(LiteratureService.class)
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        LiteratureCardCatalogService literatureCardCatalogService(LiteratureCardRepository cards,
                                                                  ObjectMapper objectMapper,
                                                                  LiteratureCardIndexService literatureCardIndexService) {
            return new LiteratureCardCatalogService(cards, objectMapper, literatureCardIndexService);
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

        @Bean
        LiteratureSource fakeSource() {
            return new FakeLiteratureSource();
        }

        @Bean
        LiteratureQueryPlanner literatureQueryPlanner() {
            return new FakeQueryPlanner();
        }

        @Bean
        LiteratureCardAnalysisService literatureCardAnalysisService() {
            return Mockito.mock(LiteratureCardAnalysisService.class);
        }

        @Bean
        LiteratureRerankService literatureRerankService() {
            LiteratureRerankService service = Mockito.mock(LiteratureRerankService.class);
            Mockito.when(service.rerank(Mockito.any(), Mockito.any(), Mockito.anyList(), Mockito.anyInt(), Mockito.anyInt()))
                    .thenReturn(LiteratureRerankService.RerankResult.empty());
            return service;
        }

        @Bean
        LiteratureRecommendationService literatureRecommendationService() {
            return Mockito.mock(LiteratureRecommendationService.class);
        }

        @Bean
        PaperStorageService paperStorageService() {
            return new PaperStorageService(new org.springframework.beans.factory.ObjectProvider<io.minio.MinioClient>() {
                @Override
                public io.minio.MinioClient getObject(Object... args) { return null; }
                @Override
                public io.minio.MinioClient getIfAvailable() { return null; }
                @Override
                public io.minio.MinioClient getIfUnique() { return null; }
                @Override
                public io.minio.MinioClient getObject() { return null; }
            }, new com.yanban.paper.config.PaperStorageProperties()) {
                @Override
                public String storeArtifact(Long userId, String type, String filename, byte[] bytes, String contentType) {
                    return "test://" + type + "/" + filename;
                }
            };
        }
    }

    private final LiteratureService literatureService;
    private final LiteratureCardRepository cards;
    private final PaperTaskLiteratureRepository relations;
    private final PaperTaskAnalysisRepository analyses;
    private final PaperTaskArtifactRepository artifacts;
    private final LiteratureRecommendationService recommendationService;

    @Autowired
    LiteratureServiceTest(LiteratureService literatureService,
                          LiteratureCardRepository cards,
                          PaperTaskLiteratureRepository relations,
                          PaperTaskAnalysisRepository analyses,
                          PaperTaskArtifactRepository artifacts,
                          LiteratureRecommendationService recommendationService) {
        this.literatureService = literatureService;
        this.cards = cards;
        this.relations = relations;
        this.analyses = analyses;
        this.artifacts = artifacts;
        this.recommendationService = recommendationService;
    }

    @Test
    void retrieveForTaskUsesRecommendationServiceAndCreatesTaskRelations() {
        ResearchProfileResult profile = new ResearchProfileResult(
                "retrieval augmented generation",
                "hybrid retrieval",
                List.of("pipeline"),
                List.of(),
                List.of(),
                List.of("MRR"),
                List.of("document question answering"),
                List.of("RAG", "retrieval"),
                false,
                "{}"
        );
        LiteratureCard rag = new LiteratureCard("hash-rag", "Hybrid retrieval for RAG");
        rag.setDoi("10.1000/rag");
        rag.setPublicationYear(2024);
        rag.setVenue("DemoConf");
        rag.setAbstractText("Retrieval augmented generation with hybrid retrieval for document question answering.");
        rag = cards.save(rag);
        LiteratureCard other = new LiteratureCard("hash-other", "Unrelated paper");
        other.setDoi("10.1000/other");
        other.setPublicationYear(2018);
        other = cards.save(other);
        Mockito.when(recommendationService.recommend(Mockito.any()))
                .thenReturn(new LiteratureRecommendationService.RecommendationResult(
                        "retrieval augmented generation; hybrid retrieval; document question answering; RAG; retrieval",
                        "为论文润色和文献推荐阶段查找真实、可引用、与研究问题直接相关的学术文献。",
                        List.of("retrieval augmented generation hybrid retrieval"),
                        20,
                        2,
                        1,
                        List.of(),
                        List.of(new LiteratureRecommendationService.RetrievalDiagnosticItem("retrieval augmented generation hybrid retrieval", "fake", 2, 2, false, "")),
                        2,
                        1,
                        true,
                        List.of(new LiteratureRecommendationService.RecommendationItem(
                                rag.getId(),
                                rag.getTitle(),
                                List.of("Alice"),
                                rag.getPublicationYear(),
                                rag.getVenue(),
                                rag.getDoi(),
                                null,
                                null,
                                null,
                                null,
                                42,
                                0.91,
                                "citation_support",
                                "retrieval augmented generation hybrid retrieval",
                                false,
                                "Matches the request",
                                null
                        )),
                        List.of(
                                new LiteratureRecommendationService.RecommendationDiagnosticItem(rag.getId(), rag.getTitle(), 0.91, "retrieval augmented generation hybrid retrieval", "citation_support"),
                                new LiteratureRecommendationService.RecommendationDiagnosticItem(other.getId(), other.getTitle(), 0.12, "retrieval augmented generation hybrid retrieval", "background")
                        )
                ));

        List<LiteratureSearchResult> selected = literatureService.retrieveForTask(99L, profile, 10, 1, 1);

        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).card().getDoi()).isEqualTo("10.1000/rag");
        assertThat(cards.findByDoi("10.1000/rag")).isPresent();
        Mockito.verify(recommendationService).recommend(Mockito.argThat(request ->
                request.query().contains("retrieval augmented generation")
                        && request.goal().contains("论文润色")
                        && request.topK() == 1
        ));
        List<PaperTaskLiterature> savedRelations = relations.findByTaskIdOrderByRelevanceScoreDesc(99L);
        assertThat(savedRelations).hasSize(2);
        assertThat(savedRelations.get(0).getSelected()).isTrue();
        assertThat(savedRelations.get(0).getRelevanceScore()).isGreaterThanOrEqualTo(savedRelations.get(1).getRelevanceScore());
        assertThat(analyses.findByTaskId(99L).orElseThrow().getConceptLadderJson()).contains("recommendation", "Hybrid retrieval for RAG");
        assertThat(artifacts.findByTaskIdOrderByCreatedAt(99L)).extracting(PaperTaskArtifact::getType)
                .contains("retrieved_literature_json", "retrieved_literature_md");
    }

    @Test
    void buildQueriesUsesProfileSeeds() {
        ResearchProfileResult profile = new ResearchProfileResult("problem", "method", List.of(), List.of(), List.of(), List.of(), List.of("task"), List.of("keyword"), false, "{}");

        assertThat(literatureService.buildQueries(profile)).contains("problem", "method", "task", "keyword", "method problem");
    }

    private static final class FakeQueryPlanner extends LiteratureQueryPlanner {
        FakeQueryPlanner() {
            super(null, new org.springframework.beans.factory.ObjectProvider<com.yanban.paper.service.PaperModelClient>() {
                @Override
                public com.yanban.paper.service.PaperModelClient getObject(Object... args) { return null; }
                @Override
                public com.yanban.paper.service.PaperModelClient getIfAvailable() { return null; }
                @Override
                public com.yanban.paper.service.PaperModelClient getIfUnique() { return null; }
                @Override
                public com.yanban.paper.service.PaperModelClient getObject() { return null; }
            }, new ObjectMapper());
        }
    }

    private static final class FakeLiteratureSource implements LiteratureSource {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public List<LiteratureCandidate> search(String query, int limit) {
            return List.of(
                    new LiteratureCandidate("fake", "10.1000/rag", null, null, null,
                            "Hybrid retrieval for RAG", List.of("Alice"), 2024, "DemoConf",
                            "Retrieval augmented generation with hybrid retrieval for document question answering.",
                            "https://doi.org/10.1000/rag", null, 42, List.of(), List.of("retrieval"), query),
                    new LiteratureCandidate("fake", "10.1000/other", null, null, null,
                            "Unrelated paper", List.of("Bob"), 2018, "DemoConf",
                            "A different topic.", "https://doi.org/10.1000/other", null, 1, List.of(), List.of(), query)
            );
        }
    }
}
