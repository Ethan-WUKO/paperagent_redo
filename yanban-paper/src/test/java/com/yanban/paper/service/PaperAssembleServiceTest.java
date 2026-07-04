package com.yanban.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.config.PaperStorageProperties;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.latex.LatexBibEntry;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexMaskingService;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.LatexSectionRole;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
@ContextConfiguration(classes = PaperAssembleServiceTest.TestConfig.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PaperAssembleServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {PaperTask.class, PaperSection.class, PaperTaskAnalysis.class, PaperTaskArtifact.class, Suggestion.class, SuggestionEvidence.class, LiteratureCard.class})
    @EnableJpaRepositories(basePackageClasses = {PaperTaskRepository.class, PaperSectionRepository.class, PaperTaskAnalysisRepository.class, PaperTaskArtifactRepository.class, SuggestionRepository.class, SuggestionEvidenceRepository.class, LiteratureCardRepository.class})
    @Import({PaperAssembleService.class, LatexMaskingService.class})
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        FakePaperStorageService paperStorageService() {
            return new FakePaperStorageService();
        }
    }

    private final PaperAssembleService assembleService;
    private final PaperTaskRepository tasks;
    private final PaperSectionRepository sections;
    private final PaperTaskArtifactRepository artifacts;
    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidence;
    private final LiteratureCardRepository cards;
    private final FakePaperStorageService storage;

    @Autowired
    PaperAssembleServiceTest(PaperAssembleService assembleService,
                             PaperTaskRepository tasks,
                             PaperSectionRepository sections,
                             PaperTaskArtifactRepository artifacts,
                             SuggestionRepository suggestions,
                             SuggestionEvidenceRepository evidence,
                             LiteratureCardRepository cards,
                             FakePaperStorageService storage) {
        this.assembleService = assembleService;
        this.tasks = tasks;
        this.sections = sections;
        this.artifacts = artifacts;
        this.suggestions = suggestions;
        this.evidence = evidence;
        this.cards = cards;
        this.storage = storage;
    }

    @Test
    void assembleAdvancedModeCreatesThreeArtifactsAndSuggestedBibFromRealEvidence() {
        PaperTask task = tasks.save(new PaperTask(7L, "Demo Paper", "main.tex", "paper/original.tex", "RUNNING", "en", "ASSEMBLE", null));
        sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 50));
        LiteratureCard card = new LiteratureCard("hash", "Grounded RAG Work");
        card.setAuthors("[\"Alice Smith\",\"Bob Lee\"]");
        card.setPublicationYear(2024);
        card.setVenue("DemoConf");
        card.setDoi("10.1000/rag");
        card.setUrl("https://doi.org/10.1000/rag");
        card = cards.save(card);
        Suggestion suggestion = new Suggestion(task.getId(), "ADVOCACY", "RelatedWork", "Use grounded evidence in related work.");
        suggestion.setApplicable(true);
        suggestion = suggestions.save(suggestion);
        evidence.save(new SuggestionEvidence(suggestion.getId(), card.getId()));

        PaperAssembleResult result = assembleService.assemble(task.getId(), document(), true);

        assertThat(result.advancedMode()).isTrue();
        assertThat(result.polishedTex()).contains("\\begin{document}", "\\title{Demo Paper}", "\\begin{abstract}", "\\section{Introduction}", "\\end{document}");
        assertThat(countOccurrences(result.polishedTex(), "\\end{document}")).isEqualTo(1);
        assertThat(result.suggestedBib()).contains("@article{alicesmith2024", "Grounded RAG Work", "10.1000/rag");
        assertThat(result.reviewReport()).contains("Paper Review Report", "Use grounded evidence", "AI-assisted self-check");
        assertThat(result.artifacts()).hasSize(4);
        assertThat(artifacts.findByTaskIdOrderByCreatedAt(task.getId())).extracting(PaperTaskArtifact::getType)
                .containsExactly("polished_tex", "suggested_bib", "suggested_bib_novel", "review_report");
        assertThat(storage.contentsByKey).hasSize(4);
        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void assembleBasicModeSkipsPolishedTexArtifactButStillWritesBibAndReport() {
        PaperTask task = tasks.save(new PaperTask(7L, "Demo Paper", "main.tex", "paper/original.tex", "RUNNING", "en", "ASSEMBLE", null));
        sections.save(new PaperSection(task.getId(), "main.tex", 0, 2, "Introduction", "INTRO", 1.0, "test", 0, 50));

        PaperAssembleResult result = assembleService.assemble(task.getId(), document(), false);

        assertThat(result.polishedTex()).isBlank();
        assertThat(result.artifacts()).hasSize(3);
        assertThat(artifacts.findByTaskIdOrderByCreatedAt(task.getId())).extracting(PaperTaskArtifact::getType)
                .containsExactly("suggested_bib", "suggested_bib_novel", "review_report");
    }

    private LatexDocument document() {
        LatexBibEntry existing = new LatexBibEntry("existing", "article", Map.of("title", "Existing"), "@article{existing,title={Existing}}", "test.bib");
        return new LatexDocument(
                "main.tex",
                "Demo Paper",
                List.of("Yanban"),
                List.of("RAG"),
                "\\documentclass{article}\n",
                "\\title{Demo Paper}\n\\begin{abstract}\nOriginal abstract.\\end{abstract}\n",
                List.of(new LatexSection(0, 2, "section", true, "Introduction", LatexSectionRole.INTRO, 0, 60,
                        "\\section{Introduction}\nThis is the original text with \\cite{existing}.\n\\end{document}")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("existing", existing),
                List.of()
        );
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    static class FakePaperStorageService extends PaperStorageService {
        private final Map<String, byte[]> contentsByKey = new LinkedHashMap<>();

        FakePaperStorageService() {
            super(new EmptyMinioProvider(), new PaperStorageProperties());
        }

        @Override
        public String storeArtifact(Long userId, String type, String filename, byte[] bytes, String contentType) {
            String key = "test/" + userId + "/" + type + "/" + Instant.now().toEpochMilli() + "-" + filename;
            contentsByKey.put(key, bytes);
            return key;
        }

        @Override
        public byte[] read(String objectKey) {
            return contentsByKey.getOrDefault(objectKey, new byte[0]);
        }

        String text(String key) {
            return new String(contentsByKey.get(key), StandardCharsets.UTF_8);
        }
    }

    static class EmptyMinioProvider implements ObjectProvider<MinioClient> {
        @Override
        public MinioClient getObject(Object... args) { return null; }

        @Override
        public MinioClient getIfAvailable() { return null; }

        @Override
        public MinioClient getIfUnique() { return null; }

        @Override
        public MinioClient getObject() { return null; }
    }
}
