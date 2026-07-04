package com.yanban.paper.latex;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LatexMaskingServiceTest {

    private final LatexMaskingService maskingService = new LatexMaskingService();

    @Test
    void maskAndUnmaskProtectsLatexCommandsAndMath() {
        String text = "We cite \\cite{a,b} and use $x+y$ in Figure~\\ref{fig:a}.";

        MaskedLatexText masked = maskingService.mask(text);
        String unmasked = maskingService.unmask(masked.text(), masked.placeholders());

        assertThat(masked.text()).contains("[[YANBAN_CITE_", "[[YANBAN_MATH_", "[[YANBAN_REF_");
        assertThat(maskingService.validatePlaceholders(masked.text(), masked.placeholderSet()).valid()).isTrue();
        assertThat(unmasked).isEqualTo(text);
    }

    @Test
    void validatePlaceholdersReportsMissingAndUnexpected() {
        MaskedLatexText masked = maskingService.mask("Text \\cite{x}.");

        LatexMaskingService.PlaceholderValidation validation = maskingService.validatePlaceholders("Text [[YANBAN_REF_9999]].", masked.placeholderSet());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.missing()).isNotEmpty();
        assertThat(validation.unexpected()).contains("[[YANBAN_REF_9999]]");
    }

    @Test
    void lintFindsUnbalancedEnvironmentAndResidualPlaceholder() {
        assertThat(maskingService.lint("\\begin{figure} [[YANBAN_CITE_0001]]"))
                .extracting(LatexLintIssue::code)
                .contains("UNBALANCED_ENV", "RESIDUAL_PLACEHOLDER");
    }
}
