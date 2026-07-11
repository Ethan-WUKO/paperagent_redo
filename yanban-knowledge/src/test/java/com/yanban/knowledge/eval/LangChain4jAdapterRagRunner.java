package com.yanban.knowledge.eval;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import java.util.function.Function;

public class LangChain4jAdapterRagRunner {

    private final Function<RagSpikeEvalCase, ContentRetriever> retrieverFactory;

    public LangChain4jAdapterRagRunner(Function<RagSpikeEvalCase, ContentRetriever> retrieverFactory) {
        this.retrieverFactory = retrieverFactory;
    }

    public BaselineRagEvaluationResult run(List<RagSpikeEvalCase> cases) {
        BaselineRagRunner runner = new BaselineRagRunner(
                "langchain4j-adapter-only",
                evalCase -> retrieve(evalCase).stream().map(this::toHit).toList()
        );
        return runner.run(cases);
    }

    private List<Content> retrieve(RagSpikeEvalCase evalCase) {
        ContentRetriever retriever = retrieverFactory.apply(evalCase);
        return retriever.retrieve(Query.from(evalCase.query()));
    }

    private BaselineRagHit toHit(Content content) {
        Metadata metadata = content.textSegment().metadata();
        return new BaselineRagHit(
                metadata.getLong(KnowledgeSearchServiceContentRetriever.META_DOCUMENT_ID),
                metadata.getString(KnowledgeSearchServiceContentRetriever.META_FILENAME),
                metadata.getInteger(KnowledgeSearchServiceContentRetriever.META_CHUNK_INDEX),
                content.textSegment().text(),
                score(content),
                metadata.getString(KnowledgeSearchServiceContentRetriever.META_CITATION_ID),
                metadata.getString(KnowledgeSearchServiceContentRetriever.META_SOURCE),
                null,
                metadata.getString(KnowledgeSearchServiceContentRetriever.META_VISIBILITY)
        );
    }

    private double score(Content content) {
        Object value = content.metadata().get(ContentMetadata.SCORE);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0d;
    }
}
