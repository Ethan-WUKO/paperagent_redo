package com.yanban.api.agent;

public record WebSearchItem(
        String title,
        String url,
        String snippet,
        Double score,
        String publishedAt,
        String source
) {
}
