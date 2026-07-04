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
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        try {
            int candidateLimit = Math.max(topK, Math.min(50, topK * 4));
            List<Double> queryVector = embeddingClient.embed(query.trim());
            List<KnowledgeSearchIndexHit> hits = searchVariants(query, userId, candidateLimit, queryVector);
            if (hits.isEmpty()) {
                return fallbackSearchService.search(query, userId, topK);
            }
            return toResults(query.trim(), hits, topK);
        } catch (Exception ex) {
            return fallbackSearchService.search(query, userId, topK);
        }
    }

    private List<KnowledgeSearchIndexHit> searchVariants(String query,
                                                         Long userId,
                                                         int candidateLimit,
                                                         List<Double> queryVector) {
        Map<String, KnowledgeSearchIndexHit> deduped = new LinkedHashMap<>();
        for (String variant : KnowledgeQueryVariants.expand(query)) {
            List<KnowledgeSearchIndexHit> variantHits = indexClient.search(variant, userId, candidateLimit, queryVector);
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

    private List<KnowledgeSearchResult> toResults(String query, List<KnowledgeSearchIndexHit> hits, int topK) {
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (KnowledgeSearchIndexHit hit : hits) {
            KbDocument document = documents.findById(hit.documentId()).orElse(null);
            if (document == null) {
                continue;
            }
            double lexicalBonus = lexicalBonus(hit.chunkText(), query);
            results.add(new KnowledgeSearchResult(
                    document.getId(),
                    document.getFilename(),
                    hit.chunkIndex(),
                    hit.chunkText(),
                    hit.vectorScore() + lexicalBonus,
                    Boolean.TRUE.equals(document.getIsPublic())
            ));
        }
        results.sort(Comparator.comparingDouble(KnowledgeSearchResult::score).reversed());
        return reranker.rerank(query, results, topK);
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
