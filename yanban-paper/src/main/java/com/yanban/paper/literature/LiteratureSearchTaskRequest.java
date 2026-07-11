package com.yanban.paper.literature;

public record LiteratureSearchTaskRequest(
        String query,
        Integer topK,
        Integer yearFrom,
        Boolean includeBibtex,
        String clientRequestId,
        Long projectId
) {
}
