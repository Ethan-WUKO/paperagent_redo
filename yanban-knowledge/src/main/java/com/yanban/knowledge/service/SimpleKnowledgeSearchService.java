package com.yanban.knowledge.service;

import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service("databaseFallbackKnowledgeSearchService")
public class SimpleKnowledgeSearchService implements KnowledgeSearchService {

    private final KbChunkRepository chunks;
    private final KbDocumentRepository documents;
    private final KnowledgeReranker reranker;

    public SimpleKnowledgeSearchService(KbChunkRepository chunks, KbDocumentRepository documents) {
        this(chunks, documents, new KnowledgeReranker());
    }

    @Autowired
    public SimpleKnowledgeSearchService(KbChunkRepository chunks,
                                        KbDocumentRepository documents,
                                        KnowledgeReranker reranker) {
        this.chunks = chunks;
        this.documents = documents;
        this.reranker = reranker;
    }

    @Override
    public List<KnowledgeSearchResult> search(String query, Long userId, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        int candidateLimit = Math.max(topK, Math.min(50, topK * 4));
        List<String> queryVariants = KnowledgeQueryVariants.expand(query);
        List<KbChunk> found = searchVariants(queryVariants, userId, candidateLimit);
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (KbChunk chunk : found) {
            KbDocument document = documents.findById(chunk.getDocumentId()).orElse(null);
            if (document == null) {
                continue;
            }
            results.add(new KnowledgeSearchResult(
                    document.getId(),
                    document.getFilename(),
                    chunk.getChunkIndex(),
                    chunk.getChunkText(),
                    score(chunk.getChunkText(), queryVariants),
                    Boolean.TRUE.equals(document.getIsPublic())
            ));
        }
        return reranker.rerank(query, results, topK);
    }

    private List<KbChunk> searchVariants(List<String> queryVariants, Long userId, int candidateLimit) {
        List<KbChunk> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String variant : queryVariants) {
            List<KbChunk> variantHits = chunks.searchAccessibleChunks(variant, userId, PageRequest.of(0, candidateLimit));
            if (variantHits == null) {
                continue;
            }
            for (KbChunk chunk : variantHits) {
                String key = chunk.getId() == null
                        ? chunk.getDocumentId() + ":" + chunk.getChunkIndex()
                        : "id:" + chunk.getId();
                if (seen.add(key)) {
                    found.add(chunk);
                }
            }
        }
        return found;
    }

    private double score(String text, List<String> queryVariants) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        double best = 1.0d;
        for (String keyword : queryVariants) {
            int count = 0;
            int from = 0;
            while (StringUtils.hasText(keyword) && (from = lower.indexOf(keyword, from)) >= 0) {
                count++;
                from += keyword.length();
            }
            if (count > 0) {
                best = Math.max(best, 1.0d + count);
            }
        }
        return best;
    }
}
