package com.yanban.paper.literature;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class StandaloneLiteratureCardSearchService {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9\\p{IsHan}]+", Pattern.CASE_INSENSITIVE);

    private final LiteratureCardRepository cards;
    private final ObjectMapper objectMapper;
    private final LiteratureCardIndexService indexService;

    public StandaloneLiteratureCardSearchService(LiteratureCardRepository cards,
                                                 ObjectMapper objectMapper,
                                                 LiteratureCardIndexService indexService) {
        this.cards = cards;
        this.objectMapper = objectMapper;
        this.indexService = indexService;
    }

    public List<LiteratureCandidate> search(String query, int limit, Integer yearFrom) {
        String normalizedQuery = normalizeQuery(query);
        if (!StringUtils.hasText(normalizedQuery) || limit <= 0) {
            return List.of();
        }
        List<String> keywords = queryKeywords(normalizedQuery);
        if (keywords.isEmpty()) {
            return List.of();
        }
        List<LiteratureCandidate> indexedHits = indexedHits(normalizedQuery, limit, yearFrom);
        if (!indexedHits.isEmpty()) {
            return indexedHits;
        }
        int perKeywordLimit = Math.max(3, Math.min(10, limit));
        Map<Long, LiteratureCandidate> unique = new LinkedHashMap<>();
        for (String keyword : keywords) {
            for (LiteratureCard card : cards.searchByKeyword(keyword, PageRequest.of(0, perKeywordLimit))) {
                if (card.getId() == null) {
                    continue;
                }
                if (yearFrom != null && card.getPublicationYear() != null && card.getPublicationYear() < yearFrom) {
                    continue;
                }
                unique.putIfAbsent(card.getId(), toCandidate(card, normalizedQuery));
            }
        }
        return unique.values().stream()
                .sorted(Comparator
                        .comparing((LiteratureCandidate candidate) -> candidate.citationCount() == null ? 0 : candidate.citationCount(), Comparator.reverseOrder())
                        .thenComparing(candidate -> candidate.year() == null ? 0 : candidate.year(), Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    private List<LiteratureCandidate> indexedHits(String normalizedQuery, int limit, Integer yearFrom) {
        List<Long> ids = indexService.searchCardIds(normalizedQuery, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, LiteratureCard> cardsById = new HashMap<>();
        cards.findAllById(ids).forEach(card -> cardsById.put(card.getId(), card));
        List<LiteratureCandidate> candidates = new ArrayList<>();
        for (Long id : ids) {
            LiteratureCard card = cardsById.get(id);
            if (card == null) {
                continue;
            }
            if (yearFrom != null && card.getPublicationYear() != null && card.getPublicationYear() < yearFrom) {
                continue;
            }
            candidates.add(toCandidate(card, normalizedQuery));
            if (candidates.size() >= limit) {
                break;
            }
        }
        return candidates;
    }

    private LiteratureCandidate toCandidate(LiteratureCard card, String sourceQuery) {
        return new LiteratureCandidate(
                "local_card",
                card.getDoi(),
                card.getArxivId(),
                card.getOpenAlexId(),
                card.getS2Id(),
                card.getTitle(),
                parseStringList(card.getAuthors()),
                card.getPublicationYear(),
                card.getVenue(),
                card.getAbstractText(),
                card.getUrl(),
                card.getPdfUrl(),
                card.getCitationCount(),
                parseStringList(card.getReferencedWorksJson()),
                parseStringList(card.getFieldsOfStudyJson()),
                sourceQuery
        );
    }

    private List<String> queryKeywords(String query) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(query.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 4) {
                keywords.add(token);
            }
            if (keywords.size() >= 6) {
                break;
            }
        }
        return List.copyOf(keywords);
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of(json);
        }
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.replaceAll("\\s+", " ").trim();
    }
}
