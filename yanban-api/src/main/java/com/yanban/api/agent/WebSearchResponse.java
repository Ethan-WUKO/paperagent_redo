package com.yanban.api.agent;

import java.util.List;

public record WebSearchResponse(
        String provider,
        String query,
        boolean degraded,
        String error,
        int requestedTopK,
        List<WebSearchItem> items,
        Integer usageCredits,
        String requestId,
        Double responseTimeSeconds
) {

    public int resultCount() {
        return items == null ? 0 : items.size();
    }
}
