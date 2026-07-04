package com.yanban.paper.literature;

import com.yanban.paper.domain.LiteratureCard;

public record LiteratureSearchResult(
        LiteratureCard card,
        double relevanceScore,
        String narrativeRole,
        String ladderNode,
        boolean selected,
        String sourceQuery
) {
}
