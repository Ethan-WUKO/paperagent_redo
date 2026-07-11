package com.yanban.paper.web;

import jakarta.validation.constraints.NotBlank;

public record PaperClarificationAnswerRequest(
        @NotBlank String answerJson
) {
}
