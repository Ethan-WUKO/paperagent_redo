package com.yanban.paper.literature;

import java.util.List;

public record LiteratureCandidate(
        String source,
        String doi,
        String arxivId,
        String openAlexId,
        String s2Id,
        String title,
        List<String> authors,
        Integer year,
        String venue,
        String abstractText,
        String url,
        String pdfUrl,
        Integer citationCount,
        List<String> referencedWorks,
        List<String> fieldsOfStudy,
        String sourceQuery
) {
}
