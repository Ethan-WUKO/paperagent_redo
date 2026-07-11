package com.yanban.knowledge.service;

import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Primary
@Service
public class HybridKnowledgeSearchService implements KnowledgeSearchService {

    private final EmbeddingClient embeddingClient;
    private final KnowledgeSearchIndexClient indexClient;
    private final KbDocumentRepository documents;
    private final SimpleKnowledgeSearchService fallbackSearchService;
    private final KnowledgeReranker reranker;

    public HybridKnowledgeSearchService(EmbeddingClient embeddingClient,
                                        KnowledgeSearchIndexClient indexClient,
                                        KbDocumentRepository documents,
                                        SimpleKnowledgeSearchService fallbackSearchService) {
        this(embeddingClient, indexClient, documents, fallbackSearchService, new KnowledgeReranker());
    }

    @Autowired
    public HybridKnowledgeSearchService(EmbeddingClient embeddingClient,
                                        KnowledgeSearchIndexClient indexClient,
                                        KbDocumentRepository documents,
                                        SimpleKnowledgeSearchService fallbackSearchService,
                                        KnowledgeReranker reranker) {
        this.embeddingClient = embeddingClient;
        this.indexClient = indexClient;
        this.documents = documents;
        this.fallbackSearchService = fallbackSearchService;
        this.reranker = reranker;
    }

    @Override
    public List<KnowledgeSearchResult> search(String query, Long userId, int topK) {
        return search(query, KnowledgeSearchOptions.activeOnly(userId, topK));
    }

    @Override
    public List<KnowledgeSearchResult> search(String query, KnowledgeSearchOptions options) {
        if (!StringUtils.hasText(query) || options == null || options.topK() <= 0) {
            return List.of();
        }
        int topK = options.topK();
        try {
            int candidateLimit = Math.max(topK, Math.min(50, topK * 4));
            List<Double> queryVector = embeddingClient.embed(query.trim());
            List<KnowledgeSearchIndexHit> hits = searchVariants(query, options, candidateLimit, queryVector);
            if (hits.isEmpty()) {
                return fallbackSearchService.search(query, options);
            }
            List<KnowledgeSearchResult> results = toResults(query.trim(), hits, options);
            return results.isEmpty() ? fallbackSearchService.search(query, options) : results;
        } catch (Exception ex) {
            return fallbackSearchService.search(query, options);
        }
    }

    private List<KnowledgeSearchIndexHit> searchVariants(String query,
                                                         KnowledgeSearchOptions options,
                                                         int candidateLimit,
                                                         List<Double> queryVector) {
        Map<String, KnowledgeSearchIndexHit> deduped = new LinkedHashMap<>();
        for (String variant : KnowledgeQueryVariants.expand(query)) {
            List<KnowledgeSearchIndexHit> variantHits = indexClient.search(variant, options, candidateLimit, queryVector);
            if (variantHits == null) {
                continue;
            }
            for (KnowledgeSearchIndexHit hit : variantHits) {
                String key = hit.documentId() + ":" + hit.chunkIndex();
                KnowledgeSearchIndexHit previous = deduped.get(key);
                if (previous == null || hit.vectorScore() > previous.vectorScore()) {
                    deduped.put(key, hit);
                }
            }
        }
        return List.copyOf(deduped.values());
    }

    private List<KnowledgeSearchResult> toResults(String query, List<KnowledgeSearchIndexHit> hits, KnowledgeSearchOptions options) {
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (KnowledgeSearchIndexHit hit : hits) {
            KbDocument document = documents.findById(hit.documentId()).orElse(null);
            if (!KnowledgeDocumentSearchPolicy.canInject(document, options)) {
                continue;
            }
            double lexicalBonus = lexicalBonus(hit.chunkText(), query);
            results.add(KnowledgeDocumentSearchPolicy.toResult(
                    document,
                    hit.chunkIndex(),
                    hit.chunkText(),
                    hit.vectorScore() + lexicalBonus
            ));
        }
        results.sort(Comparator.comparingDouble(KnowledgeSearchResult::score).reversed());
        return reranker.rerank(query, results, options.topK());
    }

    private double lexicalBonus(String text, String query) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String keyword = query.toLowerCase(Locale.ROOT);
        int count = 0;
        int from = 0;
        while ((from = lower.indexOf(keyword, from)) >= 0) {
            count++;
            from += keyword.length();
        }
        return count * 0.1d;
    }
}
