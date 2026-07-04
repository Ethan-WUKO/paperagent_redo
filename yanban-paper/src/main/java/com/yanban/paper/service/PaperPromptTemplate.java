package com.yanban.paper.service;

import java.util.Set;

public record PaperPromptTemplate(
        String name,
        String content,
        Set<String> variables
) {
}
