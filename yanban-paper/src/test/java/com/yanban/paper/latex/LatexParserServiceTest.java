package com.yanban.paper.latex;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.paper.latex.LatexLintIssue.Severity;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LatexParserServiceTest {

    private final LatexParserService parser = new LatexParserService();

    @Test
    void parseSectionsMetadataBibliographyAndProtectedSpans() {
        String tex = """
                \\documentclass{article}
                \\title{A Test Paper}
                \\author{Alice and Bob}
                \\keywords{rag, latex}
                \\begin{document}
                \\maketitle
                \\section{Introduction}
                We cite \\citep{smith2024} and refer to Figure~\\ref{fig:demo}.
                Inline math $a+b=c$ should be protected.
                \\begin{figure}
                \\includegraphics{demo.png}
                \\caption{Demo figure}
                \\label{fig:demo}
                \\end{figure}
                \\section{Method}
                Method text.
                \\end{document}
                """;
        String bib = """
                @article{smith2024,
                  title={Useful Paper},
                  author={Smith, Jane},
                  year={2024}
                }
                """;

        LatexDocument document = parser.parse("main.tex", tex, Map.of("refs.bib", bib));

        assertThat(document.title()).isEqualTo("A Test Paper");
        assertThat(document.authors()).contains("Alice", "Bob");
        assertThat(document.keywords()).contains("rag", "latex");
        assertThat(document.sections()).hasSize(2);
        assertThat(document.sections().get(0).role()).isEqualTo(LatexSectionRole.INTRO);
        assertThat(document.bibliography()).containsKey("smith2024");
        assertThat(document.citationUsages()).singleElement().satisfies(cite -> assertThat(cite.keys()).containsExactly("smith2024"));
        assertThat(document.crossReferences()).singleElement().satisfies(ref -> assertThat(ref.label()).isEqualTo("fig:demo"));
        assertThat(document.floats()).singleElement().satisfies(fig -> {
            assertThat(fig.kind()).isEqualTo("figure");
            assertThat(fig.label()).isEqualTo("fig:demo");
            assertThat(fig.caption()).isEqualTo("Demo figure");
            assertThat(fig.graphics()).containsExactly("demo.png");
        });
        assertThat(document.protectedSpans()).extracting(LatexProtectedSpan::kind)
                .contains("CITE", "REF", "LABEL", "GRAPHICS", "MATH", "ENV");
        assertThat(document.lintIssues()).isEmpty();
    }

    @Test
    void parseInlineThebibliographyEntries() {
        String tex = """
                \\documentclass{article}
                \\begin{document}
                \\section{Intro}
                Citation \\cite{inline-key}.
                \\begin{thebibliography}{99}
                \\bibitem{inline-key} Doe. Inline reference. 2024.
                \\end{thebibliography}
                \\end{document}
                """;

        LatexDocument document = parser.parse("main.tex", tex, Map.of());

        assertThat(document.bibliography()).containsKey("inline-key");
        assertThat(document.bibliography().get("inline-key").type()).isEqualTo("bibitem");
        assertThat(document.lintIssues()).isEmpty();
    }

    @Test
    void reportDanglingCitationAndReferenceAsBlockers() {
        String tex = """
                \\documentclass{article}
                \\begin{document}
                \\section{Intro}
                Missing citation \\cite{missing-key} and missing ref \\ref{fig:missing}.
                \\end{document}
                """;

        LatexDocument document = parser.parse("main.tex", tex, Map.of());

        assertThat(document.lintIssues()).extracting(LatexLintIssue::code)
                .contains("DANGLING_CITE", "DANGLING_REF");
        assertThat(document.lintIssues()).allSatisfy(issue -> assertThat(issue.severity()).isEqualTo(Severity.BLOCKER));
    }

    @Test
    void reportEnvironmentMismatchAndDuplicateBibKeys() {
        String tex = """
                \\documentclass{article}
                \\begin{document}
                \\section{Intro}
                Text.
                \\begin{figure}
                \\end{table}
                \\end{document}
                """;
        String bib = """
                @article{dup, title={One}}
                @inproceedings{dup, title={Two}}
                """;

        LatexDocument document = parser.parse("main.tex", tex, Map.of("refs.bib", bib));

        assertThat(document.lintIssues()).extracting(LatexLintIssue::code)
                .contains("ENV_MISMATCH", "DUPLICATE_BIB_KEY");
    }
}
