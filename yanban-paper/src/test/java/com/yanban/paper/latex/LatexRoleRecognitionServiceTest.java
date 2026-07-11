package com.yanban.paper.latex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LatexRoleRecognitionServiceTest {

    private final LatexParserService parser = new LatexParserService();
    private final LatexRoleRecognitionService service = new LatexRoleRecognitionService();

    @Test
    void detectsRelatedWorkIntegratedInIntroductionAsClarification() {
        String tex = """
                \\documentclass{article}
                \\begin{document}
                \\section{Introduction}
                Prior work has studied this problem \\cite{a}. Existing methods also explore it \\cite{b}.
                \\section{Method}
                Method.
                \\section{Experiments}
                Experiments.
                \\section{Conclusion}
                Conclusion.
                \\end{document}
                """;
        String bib = "@article{a,title={A}}\n@article{b,title={B}}";

        RoleRecognitionResult result = service.recognize(parser.parse("main.tex", tex, Map.of("refs.bib", bib)));

        assertThat(result.sectionRoles()).extracting(RecognizedSectionRole::role)
                .contains(LatexSectionRole.INTRO, LatexSectionRole.METHOD, LatexSectionRole.EXPERIMENTS, LatexSectionRole.CONCLUSION);
        assertThat(result.clarifications()).singleElement().satisfies(candidate -> {
            assertThat(candidate.type()).isEqualTo("RELATED_WORK_PLACEMENT");
            assertThat(candidate.blocking()).isTrue();
            assertThat(candidate.defaultOption()).isEqualTo("KEEP_IN_INTRO");
        });
        assertThat(result.missingRoles()).isEmpty();
    }

    @Test
    void referenceBeampatternIsNotReferencesSection() {
        String tex = """
                \\documentclass{article}
                \\begin{document}
                \\section{Introduction}
                Intro.
                \\subsection{Subproblem 3: SDP-Based Reference Beampattern Shaping}
                This subsection defines a reference beampattern for optimization.
                \\section{References}
                \\bibliography{refs}
                \\end{document}
                """;

        RoleRecognitionResult result = service.recognize(parser.parse("main.tex", tex, Map.of()));

        assertThat(result.sectionRoles()).anySatisfy(role -> {
            assertThat(role.title()).contains("Reference Beampattern");
            assertThat(role.role()).isNotEqualTo(LatexSectionRole.REFERENCES);
        });
        assertThat(result.sectionRoles()).anySatisfy(role -> {
            assertThat(role.title()).isEqualTo("References");
            assertThat(role.role()).isEqualTo(LatexSectionRole.REFERENCES);
        });
    }

    @Test
    void doesNotAskWhenRelatedWorkIsExplicitSection() {
        String tex = """
                \\documentclass{article}
                \\begin{document}
                \\section{Introduction}
                Intro.
                \\section{Related Work}
                Related \\cite{a}.
                \\section{Method}
                Method.
                \\end{document}
                """;

        RoleRecognitionResult result = service.recognize(parser.parse("main.tex", tex, Map.of("refs.bib", "@article{a,title={A}}")));

        assertThat(result.sectionRoles()).extracting(RecognizedSectionRole::role)
                .contains(LatexSectionRole.RELATED_WORK);
        assertThat(result.clarifications()).isEmpty();
    }
}
