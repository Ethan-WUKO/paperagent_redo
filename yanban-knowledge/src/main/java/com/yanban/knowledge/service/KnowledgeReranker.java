package com.yanban.knowledge.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeReranker {

    private static final int MAX_REASON_TERMS = 6;
    private static final Set<String> STOP_WORDS = Set.of(
            "answer", "base", "citation", "contains", "cited", "exact", "fact", "find",
            "for", "from", "knowledge", "please", "return", "source", "supports",
            "the", "uploaded", "using", "verify", "which"
    );

    public List<KnowledgeSearchResult> rerank(String query, List<KnowledgeSearchResult> candidates, int topK) {
        if (!StringUtils.hasText(query) || candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<String> terms = tokenize(query);
        List<String> phrases = KnowledgeQueryVariants.expand(query);
        String normalizedQuery = normalize(query);
        Map<String, KnowledgeSearchResult> deduped = new LinkedHashMap<>();
        for (KnowledgeSearchResult candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String key = candidate.documentId() + ":" + candidate.chunkIndex();
            KnowledgeSearchResult previous = deduped.get(key);
            if (previous == null || candidate.score() > previous.score()) {
                deduped.put(key, candidate);
            }
        }
        return deduped.values().stream()
                .map(candidate -> rerankCandidate(candidate, normalizedQuery, phrases, terms))
                .sorted(Comparator.comparingDouble((KnowledgeSearchResult item) ->
                        item.rerankScore() == null ? item.score() : item.rerankScore()).reversed())
                .limit(topK)
                .toList();
    }

    private KnowledgeSearchResult rerankCandidate(KnowledgeSearchResult candidate,
                                                  String normalizedQuery,
                                                  List<String> phrases,
                                                  List<String> terms) {
        String text = normalize(candidate.chunkText());
        String filename = normalize(candidate.filename());
        Set<String> matchedTerms = new LinkedHashSet<>();
        for (String term : terms) {
            if (text.contains(term) || filename.contains(term)) {
                matchedTerms.add(term);
            }
        }
        double exactPhrase = hasExactPhrase(text, normalizedQuery, phrases) ? 1.0d : 0.0d;
        double lookupTokenMatch = terms.stream()
                .filter(term -> term.contains("_"))
                .anyMatch(term -> text.contains(term) || filename.contains(term)) ? 1.0d : 0.0d;
        double termCoverage = terms.isEmpty() ? 0.0d : (double) matchedTerms.size() / terms.size();
        double filenameMatch = terms.stream().anyMatch(filename::contains) ? 1.0d : 0.0d;
        double positionBonus = candidate.chunkIndex() == null ? 0.0d : 1.0d / (1 + Math.max(0, candidate.chunkIndex()));
        double rerankScore = candidate.score() * 0.35d
                + exactPhrase * 2.0d
                + lookupTokenMatch * 2.0d
                + termCoverage * 1.5d
                + filenameMatch * 0.25d
                + positionBonus * 0.05d;
        return candidate.withRerank(rerankScore, reason(exactPhrase, lookupTokenMatch, termCoverage, filenameMatch, matchedTerms));
    }

    private String reason(double exactPhrase,
                          double lookupTokenMatch,
                          double termCoverage,
                          double filenameMatch,
                          Set<String> matchedTerms) {
        List<String> parts = new ArrayList<>();
        if (exactPhrase > 0.0d) {
            parts.add("exact_phrase");
        }
        if (lookupTokenMatch > 0.0d) {
            parts.add("lookup_token_match");
        }
        if (termCoverage > 0.0d) {
            parts.add("term_coverage=" + String.format(Locale.ROOT, "%.2f", termCoverage));
        }
        if (filenameMatch > 0.0d) {
            parts.add("filename_match");
        }
        if (!matchedTerms.isEmpty()) {
            parts.add("matched_terms=" + String.join(",", matchedTerms.stream().limit(MAX_REASON_TERMS).toList()));
        }
        return parts.isEmpty() ? "vector_or_database_score_only" : String.join("; ", parts);
    }

    private boolean hasExactPhrase(String text, String normalizedQuery, List<String> phrases) {
        if (StringUtils.hasText(normalizedQuery) && text.contains(normalizedQuery)) {
            return true;
        }
        return phrases.stream()
                .filter(phrase -> phrase.length() >= 6 && (phrase.contains("_") || !phrase.matches("\\d+")))
                .anyMatch(text::contains);
    }

    private List<String> tokenize(String query) {
        String normalized = normalize(query);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String term : normalized.split("[^a-z0-9_]+")) {
            if (term.length() >= 2 && !STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return List.copyOf(terms);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
