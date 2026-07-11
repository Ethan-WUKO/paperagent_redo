package com.yanban.knowledge.rag;

import com.yanban.core.model.ChatMessage;
import com.yanban.core.rag.KnowledgeContextProvider;
import com.yanban.core.rag.KnowledgeSnippet;
import com.yanban.knowledge.eval.KnowledgeSearchServiceContentRetriever;
import com.yanban.knowledge.service.KnowledgeSearchService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class KnowledgeSearchContextProvider implements KnowledgeContextProvider {

    private final KnowledgeSearchService searchService;
    private final HeuristicCompressingChatModel queryTransformerModel = new HeuristicCompressingChatModel();

    public KnowledgeSearchContextProvider(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public List<KnowledgeSnippet> searchContext(String query, Long userId, int topK) {
        return searchContext(query, userId, topK, List.of());
    }

    @Override
    public List<KnowledgeSnippet> searchContext(String query, Long userId, int topK, List<ChatMessage> history) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        KnowledgeSearchServiceContentRetriever retriever =
                new KnowledgeSearchServiceContentRetriever(searchService, userId, topK);
        DefaultRetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(new CompressingQueryTransformer(queryTransformerModel))
                .queryRouter(new DefaultQueryRouter(retriever))
                .contentAggregator(new DefaultContentAggregator())
                .contentInjector(new DefaultContentInjector())
                .build();
        UserMessage userMessage = UserMessage.from(query);
        AugmentationResult result = augmentor.augment(new AugmentationRequest(
                userMessage,
                dev.langchain4j.rag.query.Metadata.from(userMessage, userId, toLangChainHistory(history))
        ));
        List<Content> contents = result == null || result.contents() == null ? List.of() : result.contents();
        return contents.stream()
                .map(this::toSnippet)
                .toList();
    }

    private List<dev.langchain4j.data.message.ChatMessage> toLangChainHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .filter(message -> message != null && StringUtils.hasText(message.content()))
                .map(message -> {
                    String role = message.role() == null ? "" : message.role().trim().toLowerCase(Locale.ROOT);
                    return switch (role) {
                        case "assistant" -> AiMessage.from(message.content());
                        case "system" -> SystemMessage.from(message.content());
                        default -> UserMessage.from(message.content());
                    };
                })
                .toList();
    }

    private KnowledgeSnippet toSnippet(Content content) {
        Metadata metadata = content.textSegment().metadata();
        Object score = content.metadata().get(ContentMetadata.SCORE);
        double numericScore = score instanceof Number number ? number.doubleValue() : 0.0d;
        return new KnowledgeSnippet(
                metadata.getLong(KnowledgeSearchServiceContentRetriever.META_DOCUMENT_ID),
                metadata.getString(KnowledgeSearchServiceContentRetriever.META_FILENAME),
                metadata.getInteger(KnowledgeSearchServiceContentRetriever.META_CHUNK_INDEX),
                content.textSegment().text(),
                numericScore,
                metadata.getString(KnowledgeSearchServiceContentRetriever.META_CITATION_ID),
                scoreBand(numericScore),
                metadata.getString(KnowledgeSearchServiceContentRetriever.META_SOURCE),
                numericScore,
                "langchain4j_augmentor"
        );
    }

    private String scoreBand(double score) {
        if (score >= 3.0d) {
            return "high";
        }
        if (score >= 1.5d) {
            return "medium";
        }
        return "low";
    }
}
