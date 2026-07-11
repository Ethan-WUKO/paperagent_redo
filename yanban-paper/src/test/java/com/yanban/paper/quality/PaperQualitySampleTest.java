package com.yanban.paper.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexLintIssue;
import com.yanban.paper.latex.LatexParserService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaperQualitySampleTest {

    private final LatexParserService parser = new LatexParserService();

    @Test
    void chineseSampleParsesWithBibliographyAndProtectedElements() throws IOException {
        Path root = sampleRoot("zh-rag-polish");
        LatexDocument document = parser.parse(
                "main.tex",
                read(root.resolve("main.tex")),
                Map.of("refs.bib", read(root.resolve("refs.bib")))
        );

        assertThat(document.title()).contains("检索增强问答系统");
        assertThat(document.sections()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(document.bibliography()).containsKey("lewis2020rag");
        assertThat(document.citationUsages()).anySatisfy(cite -> assertThat(cite.keys()).contains("lewis2020rag"));
        assertThat(document.crossReferences()).anySatisfy(ref -> assertThat(ref.label()).isEqualTo("fig:pipeline"));
        assertThat(document.floats()).anySatisfy(fig -> assertThat(fig.label()).isEqualTo("fig:pipeline"));
        assertThat(document.protectedSpans()).extracting(span -> span.kind())
                .contains("CITE", "REF", "LABEL", "GRAPHICS", "MATH", "ENV");
        assertNoBlockers(document);
    }

    @Test
    void englishSampleParsesWithEquationReferenceAndBibliography() throws IOException {
        Path root = sampleRoot("en-literature-gap");
        LatexDocument document = parser.parse(
                "main.tex",
                read(root.resolve("main.tex")),
                Map.of("refs.bib", read(root.resolve("refs.bib")))
        );

        assertThat(document.title()).contains("Evidence-Grounded Literature Assistance");
        assertThat(document.sections()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(document.bibliography()).containsKey("lewis2020rag");
        assertThat(document.citationUsages()).anySatisfy(cite -> assertThat(cite.keys()).contains("lewis2020rag"));
        assertThat(document.crossReferences()).anySatisfy(ref -> assertThat(ref.label()).isEqualTo("eq:score"));
        assertThat(document.protectedSpans()).extracting(span -> span.kind())
                .contains("CITE", "REF", "LABEL", "MATH", "ENV");
        assertNoBlockers(document);
    }

    @Test
    void inlineBibliographySampleParsesWithoutExternalBibFile() throws IOException {
        Path root = sampleRoot("inline-bibliography");
        LatexDocument document = parser.parse("main.tex", read(root.resolve("main.tex")), Map.of());

        assertThat(document.title()).contains("Inline Bibliography Sample");
        assertThat(document.bibliography()).containsKey("doe2024inline");
        assertThat(document.bibliography().get("doe2024inline").type()).isEqualTo("bibitem");
        assertThat(document.citationUsages()).anySatisfy(cite -> assertThat(cite.keys()).contains("doe2024inline"));
        assertNoBlockers(document);
    }

    private static Path sampleRoot(String name) {
        return Path.of("src/test/resources/paper-quality-samples", name);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void assertNoBlockers(LatexDocument document) {
        assertThat(document.lintIssues())
                .filteredOn(issue -> issue.severity() == LatexLintIssue.Severity.BLOCKER)
                .isEmpty();
    }
}
