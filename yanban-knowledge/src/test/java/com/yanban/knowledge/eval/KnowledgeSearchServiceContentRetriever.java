package com.yanban.knowledge.eval;

import com.yanban.knowledge.service.KnowledgeSearchResult;
import com.yanban.knowledge.service.KnowledgeSearchService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.Map;
import java.util.Objects;
import java.util.List;

public class KnowledgeSearchServiceContentRetriever implements ContentRetriever {

    public static final String META_DOCUMENT_ID = "documentId";
    public static final String META_FILENAME = "filename";
    public static final String META_CHUNK_INDEX = "chunkIndex";
    public static final String META_CITATION_ID = "citationId";
    public static final String META_SOURCE = "source";
    public static final String META_VISIBILITY = "visibility";
    public static final String META_SOURCE_TYPE = "sourceType";
    public static final String META_VERSION_STATUS = "versionStatus";
    public static final String META_LINEAGE_ID = "lineageId";
    public static final String META_VERSION_NO = "versionNo";
    public static final String META_PROJECT_ID = "projectId";
    public static final String META_CANONICAL_KEY = "canonicalKey";

    private final KnowledgeSearchService searchService;
    private final Long userId;
    private final int topK;

    public KnowledgeSearchServiceContentRetriever(KnowledgeSearchService searchService, Long userId, int topK) {
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.userId = userId;
        this.topK = Math.max(1, topK);
    }

    @Override
    public List<Content> retrieve(Query query) {
        String queryText = query == null ? null : query.text();
        return searchService.search(queryText, userId, topK).stream()
                .map(this::toContent)
                .toList();
    }

    private Content toContent(KnowledgeSearchResult result) {
        Metadata metadata = new Metadata()
                .put(META_DOCUMENT_ID, result.documentId() == null ? -1L : result.documentId())
                .put(META_FILENAME, nullToBlank(result.filename()))
                .put(META_CHUNK_INDEX, result.chunkIndex() == null ? 0 : result.chunkIndex())
                .put(META_CITATION_ID, nullToBlank(result.citationId()))
                .put(META_SOURCE, nullToBlank(result.source()))
                .put(META_VISIBILITY, result.isPublic() ? "PUBLIC" : "PRIVATE")
                .put(META_SOURCE_TYPE, nullToBlank(result.sourceType()))
                .put(META_VERSION_STATUS, nullToBlank(result.versionStatus()))
                .put(META_LINEAGE_ID, nullToBlank(result.lineageId()))
                .put(META_VERSION_NO, result.versionNo() == null ? 1 : result.versionNo())
                .put(META_PROJECT_ID, result.projectId() == null ? -1L : result.projectId())
                .put(META_CANONICAL_KEY, nullToBlank(result.canonicalKey()));
        TextSegment segment = TextSegment.from(nullToBlank(result.chunkText()), metadata);
        return Content.from(segment, Map.of(ContentMetadata.SCORE, result.score()));
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
