package com.yanban.knowledge.eval;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import java.util.List;
import java.util.function.Function;
import org.springframework.util.StringUtils;

public class LangChain4jAugmentorRagRunner {

    private final Function<RagSpikeEvalCase, ContentRetriever> retrieverFactory;
    private final ChatModel chatModel;
    private final boolean usePreviousContext;

    public LangChain4jAugmentorRagRunner(Function<RagSpikeEvalCase, ContentRetriever> retrieverFactory,
                                         ChatModel chatModel) {
        this(retrieverFactory, chatModel, true);
    }

    public LangChain4jAugmentorRagRunner(Function<RagSpikeEvalCase, ContentRetriever> retrieverFactory,
                                         ChatModel chatModel,
                                         boolean usePreviousContext) {
        this.retrieverFactory = retrieverFactory;
        this.chatModel = chatModel;
        this.usePreviousContext = usePreviousContext;
    }

    public BaselineRagEvaluationResult run(List<RagSpikeEvalCase> cases) {
        BaselineRagRunner runner = new BaselineRagRunner(
                usePreviousContext ? "langchain4j-augmentor-contextual" : "langchain4j-augmentor-current",
                evalCase -> retrieve(evalCase).stream().map(this::toHit).toList()
        );
        return runner.run(cases);
    }

    private List<Content> retrieve(RagSpikeEvalCase evalCase) {
        ContentRetriever retriever = retrieverFactory.apply(evalCase);
        DefaultRetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(new CompressingQueryTransformer(chatModel))
                .queryRouter(new DefaultQueryRouter(retriever))
                .contentAggregator(new DefaultContentAggregator())
                .contentInjector(new DefaultContentInjector())
                .build();
        UserMessage userMessage = UserMessage.from(evalCase.query());
        List<ChatMessage> chatMemory = usePreviousContext && StringUtils.hasText(evalCase.previousContext())
                ? List.of(UserMessage.from(evalCase.previousContext()))
                : List.of();
        AugmentationResult result = augmentor.augment(new AugmentationRequest(
                userMessage,
                dev.langchain4j.rag.query.Metadata.from(userMessage, evalCase.userId(), chatMemory)
        ));
        return result == null || result.contents() == null ? List.of() : result.contents();
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
                metadata.getString(KnowledgeSearchServiceContentRetriever.META_VERSION_STATUS),
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
