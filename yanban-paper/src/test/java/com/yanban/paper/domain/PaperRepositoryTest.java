package com.yanban.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = PaperRepositoryTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PaperRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {PaperTask.class, PaperTaskRound.class, PaperSection.class, PaperTaskClarification.class, PaperTaskAnalysis.class, PaperTaskArtifact.class, LiteratureCard.class, PaperTaskLiterature.class, Suggestion.class, SuggestionEvidence.class})
    @EnableJpaRepositories(basePackageClasses = {PaperTaskRepository.class, PaperTaskRoundRepository.class, PaperSectionRepository.class, PaperTaskClarificationRepository.class, PaperTaskAnalysisRepository.class, PaperTaskArtifactRepository.class, LiteratureCardRepository.class, PaperTaskLiteratureRepository.class, SuggestionRepository.class, SuggestionEvidenceRepository.class})
    static class TestConfig {
    }

    private final PaperTaskRepository tasks;
    private final PaperTaskRoundRepository rounds;
    private final PaperSectionRepository sections;
    private final PaperTaskClarificationRepository clarifications;
    private final PaperTaskAnalysisRepository analyses;
    private final LiteratureCardRepository literatureCards;
    private final PaperTaskLiteratureRepository taskLiterature;
    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository suggestionEvidence;
    private final PaperTaskArtifactRepository artifacts;

    @Autowired
    PaperRepositoryTest(PaperTaskRepository tasks, PaperTaskRoundRepository rounds,
                        PaperSectionRepository sections, PaperTaskClarificationRepository clarifications,
                        PaperTaskAnalysisRepository analyses,
                        LiteratureCardRepository literatureCards,
                        PaperTaskLiteratureRepository taskLiterature,
                        SuggestionRepository suggestions,
                        SuggestionEvidenceRepository suggestionEvidence,
                        PaperTaskArtifactRepository artifacts) {
        this.tasks = tasks;
        this.rounds = rounds;
        this.sections = sections;
        this.clarifications = clarifications;
        this.analyses = analyses;
        this.literatureCards = literatureCards;
        this.taskLiterature = taskLiterature;
        this.suggestions = suggestions;
        this.suggestionEvidence = suggestionEvidence;
        this.artifacts = artifacts;
    }

    @Test
    void insertPaperTaskAndRoundsThenQuery() {
        PaperTask task = tasks.save(new PaperTask(2001L, "论文任务", "paper.docx", "papers/task-1.docx", "PROCESSING", "zh", "SUMMARY", null));
        rounds.save(new PaperTaskRound(task.getId(), 1, "SUMMARY", "SUCCESS", "input", "output", "notes"));
        sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 0.8, "heuristic", 10, 120));
        clarifications.save(new PaperTaskClarification(task.getId(), "RELATED_WORK_PLACEMENT", "{}", "{}", "PENDING"));
        PaperTaskAnalysis analysis = new PaperTaskAnalysis(task.getId());
        analysis.setResearchProfileJson("{\"problem\":\"demo\"}");
        analyses.save(analysis);
        LiteratureCard card = new LiteratureCard("hash-demo", "Demo Literature");
        card.setDoi("10.1000/demo");
        card = literatureCards.save(card);
        PaperTaskLiterature relation = new PaperTaskLiterature(task.getId(), card.getId());
        relation.setRelevanceScore(0.8);
        relation.setSelected(true);
        taskLiterature.save(relation);
        Suggestion suggestion = new Suggestion(task.getId(), "ADVOCACY", "RelatedWork", "Add grounded related work.");
        suggestion.setApplicable(true);
        suggestion = suggestions.save(suggestion);
        suggestionEvidence.save(new SuggestionEvidence(suggestion.getId(), card.getId()));
        PaperTaskArtifact artifact = new PaperTaskArtifact(task.getId(), "review_report", "paper/1/review.md", 1);
        artifact.setMetadataJson("{\"size\":12}");
        artifacts.save(artifact);

        List<PaperTask> savedTasks = tasks.findByUserIdOrderByCreatedAtDesc(2001L);
        List<PaperTaskRound> savedRounds = rounds.findByTaskIdOrderByCreatedAtAsc(task.getId());
        List<PaperSection> savedSections = sections.findByTaskIdOrderByOrderIndexAsc(task.getId());
        List<PaperTaskClarification> savedClarifications = clarifications.findByTaskIdAndStatusOrderByCreatedAtAsc(task.getId(), "PENDING");
        PaperTaskAnalysis savedAnalysis = analyses.findByTaskId(task.getId()).orElseThrow();
        LiteratureCard savedCard = literatureCards.findByDoi("10.1000/demo").orElseThrow();
        PaperTaskLiterature savedRelation = taskLiterature.findByTaskIdAndCardId(task.getId(), savedCard.getId()).orElseThrow();
        Suggestion savedSuggestion = suggestions.findByTaskIdOrderByCreatedAt(task.getId()).get(0);
        List<SuggestionEvidence> savedEvidence = suggestionEvidence.findBySuggestionId(savedSuggestion.getId());
        PaperTaskArtifact savedArtifact = artifacts.findFirstByTaskIdAndTypeOrderByVersionDesc(task.getId(), "review_report").orElseThrow();

        assertThat(savedTasks).hasSize(1);
        assertThat(savedTasks.get(0).getTitle()).isEqualTo("论文任务");
        assertThat(savedRounds).hasSize(1);
        assertThat(savedRounds.get(0).getStage()).isEqualTo("SUMMARY");
        assertThat(savedSections).hasSize(1);
        assertThat(savedSections.get(0).getRole()).isEqualTo("INTRO");
        assertThat(savedClarifications).hasSize(1);
        assertThat(savedClarifications.get(0).getType()).isEqualTo("RELATED_WORK_PLACEMENT");
        assertThat(savedAnalysis.getResearchProfileJson()).contains("demo");
        assertThat(savedCard.getTitle()).isEqualTo("Demo Literature");
        assertThat(savedRelation.getSelected()).isTrue();
        assertThat(savedSuggestion.getTrack()).isEqualTo("ADVOCACY");
        assertThat(savedEvidence).singleElement().satisfies(evidence -> assertThat(evidence.getCardId()).isEqualTo(savedCard.getId()));
        assertThat(savedArtifact.getObjectKey()).isEqualTo("paper/1/review.md");
    }
}
