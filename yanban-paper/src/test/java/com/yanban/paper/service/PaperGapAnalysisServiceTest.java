package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskLiterature;
import com.yanban.paper.domain.PaperTaskLiteratureRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import java.util.List;
import java.util.Set;
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
@ContextConfiguration(classes = PaperGapAnalysisServiceTest.TestConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PaperGapAnalysisServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {PaperTask.class, PaperTaskAnalysis.class, LiteratureCard.class, PaperTaskLiterature.class, Suggestion.class, SuggestionEvidence.class})
    @EnableJpaRepositories(basePackageClasses = {PaperTaskRepository.class, PaperTaskAnalysisRepository.class, LiteratureCardRepository.class, PaperTaskLiteratureRepository.class, SuggestionRepository.class, SuggestionEvidenceRepository.class})
    @Import({PaperGapAnalysisService.class, PaperPromptService.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        PaperModelClient paperModelClient() {
            return (systemPrompt, userPrompt, temperature, maxTokens) -> """
                    {
                      "suggestions": [
                        {
                          "track": "ADVOCACY",
                          "category": "RelatedWork",
                          "severity": "minor",
                          "statement": "Use retrieved work to position the method.",
                          "evidence": ["card-1"],
                          "applicable": true,
                          "patch": {"contentType":"A","anchor":"RelatedWork:end","latexSnippet":"Grounded text."}
                        },
                        {
                          "track": "ADVOCACY",
                          "category": "Experiment",
                          "severity": "major",
                          "statement": "Invent a missing experiment.",
                          "evidence": ["card-999"],
                          "applicable": true,
                          "patch": {"contentType":"A","anchor":"Experiments:end","latexSnippet":"Fake result."}
                        }
                      ]
                    }
                    """;
        }
    }

    private final PaperGapAnalysisService gapAnalysisService;
    private final PaperTaskRepository tasks;
    private final PaperTaskAnalysisRepository analyses;
    private final LiteratureCardRepository cards;
    private final PaperTaskLiteratureRepository taskLiterature;
    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidenceRepository;

    @Autowired
    PaperGapAnalysisServiceTest(PaperGapAnalysisService gapAnalysisService,
                                PaperTaskRepository tasks,
                                PaperTaskAnalysisRepository analyses,
                                LiteratureCardRepository cards,
                                PaperTaskLiteratureRepository taskLiterature,
                                SuggestionRepository suggestions,
                                SuggestionEvidenceRepository evidenceRepository) {
        this.gapAnalysisService = gapAnalysisService;
        this.tasks = tasks;
        this.analyses = analyses;
        this.cards = cards;
        this.taskLiterature = taskLiterature;
        this.suggestions = suggestions;
        this.evidenceRepository = evidenceRepository;
    }

    @Test
    void generateAndSavePersistsGroundedSuggestionsAndConvertsUngroundedAdvocacy() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null));
        PaperTaskAnalysis analysis = new PaperTaskAnalysis(task.getId());
        analysis.setResearchProfileJson("{\"problem\":\"RAG\",\"method\":\"hybrid retrieval\"}");
        analysis.setConceptLadderJson("{\"advocacy\":[]}");
        analyses.save(analysis);
        LiteratureCard card = new LiteratureCard("hash-rag", "Hybrid Retrieval for RAG");
        card.setAbstractText("Hybrid retrieval improves retrieval augmented generation grounding.");
        card = cards.save(card);
        Long cardId = card.getId();
        PaperTaskLiterature relation = new PaperTaskLiterature(task.getId(), cardId);
        relation.setSelected(true);
        relation.setRelevanceScore(0.9);
        relation.setNarrativeRole("advocacy");
        taskLiterature.save(relation);

        List<GapSuggestionResult> results = gapAnalysisService.generateAndSave(task.getId(), "Missing Related Work section.", "en");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).track()).isEqualTo("ADVOCACY");
        assertThat(results.get(0).applicable()).isTrue();
        assertThat(results.get(0).evidenceCardIds()).containsExactly(cardId);
        assertThat(results.get(1).track()).isEqualTo("CRITIQUE");
        assertThat(results.get(1).applicable()).isFalse();
        assertThat(results.get(1).statement()).contains("Converted from ungrounded advocacy");
        assertThat(suggestions.findByTaskIdOrderByCreatedAt(task.getId())).hasSize(2);
        assertThat(evidenceRepository.findBySuggestionId(results.get(0).suggestionId())).singleElement()
                .satisfies(evidence -> assertThat(evidence.getCardId()).isEqualTo(cardId));
        assertThat(analyses.findByTaskId(task.getId()).orElseThrow().getGapMatrixJson()).contains("gap-analysis-v1");
    }

    @Test
    void parseAndSaveIgnoresEvidenceOutsideAllowedCards() {
        PaperTask task = tasks.save(new PaperTask(1L, "RAG Paper", "main.tex", "paper/main.tex", "RUNNING", "en", "PROFILE", null));
        String json = """
                {"suggestions":[{"track":"ADVOCACY","category":"RelatedWork","statement":"Unsupported","evidence":["card-123"],"applicable":true,"patch":{"latexSnippet":"x"}}]}
                """;

        List<GapSuggestionResult> results = gapAnalysisService.parseAndSave(task.getId(), json, Set.of(1L));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.track()).isEqualTo("CRITIQUE");
            assertThat(result.evidenceCardIds()).isEmpty();
            assertThat(result.applicable()).isFalse();
        });
    }
}
