package com.yanban.paper.literature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdHocLiteratureSearchService {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9\\p{IsHan}]+", Pattern.CASE_INSENSITIVE);

    private final List<LiteratureSource> sources;

    public AdHocLiteratureSearchService(List<LiteratureSource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public AdHocLiteratureSearchResult search(String query, int limit, Integer yearFrom) {
        String normalizedQuery = normalizeQuery(query);
        int selectionLimit = Math.max(1, Math.min(50, limit <= 0 ? 8 : limit));
        int perSourceLimit = Math.max(5, Math.min(30, selectionLimit * 2));
        if (!StringUtils.hasText(normalizedQuery)) {
            return new AdHocLiteratureSearchResult("", List.of(), 0, 0, 0, List.of("empty_query"));
        }
        Map<String, LiteratureCandidate> unique = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int rawCount = 0;
        int sourceAttempts = 0;
        for (LiteratureSource source : sources) {
            sourceAttempts++;
            List<LiteratureCandidate> candidates;
            try {
                candidates = source.search(normalizedQuery, perSourceLimit);
            } catch (Exception ex) {
                failures.add(source.name() + ": " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                candidates = List.of();
            }
            rawCount += candidates.size();
            for (LiteratureCandidate candidate : candidates) {
                if (!StringUtils.hasText(candidate.title())) continue;
                if (yearFrom != null && candidate.year() != null && candidate.year() < yearFrom) continue;
                unique.putIfAbsent(identityKey(candidate), candidate);
            }
        }
        Set<String> queryTokens = queryTokens(normalizedQuery);
        List<AdHocLiteratureItem> items = unique.values().stream()
                .map(candidate -> toItem(candidate, score(candidate, queryTokens)))
                .sorted(Comparator.comparingDouble(AdHocLiteratureItem::score).reversed()
                        .thenComparing(item -> item.year() == null ? 0 : item.year(), Comparator.reverseOrder()))
                .limit(selectionLimit)
                .toList();
        return new AdHocLiteratureSearchResult(normalizedQuery, items, rawCount, unique.size(), sourceAttempts, failures);
    }

    private AdHocLiteratureItem toItem(LiteratureCandidate candidate, double score) {
        return new AdHocLiteratureItem(
                candidate.title(),
                candidate.authors() == null ? List.of() : candidate.authors(),
                candidate.year(),
                candidate.venue(),
                normalizeDoi(candidate.doi()),
                candidate.arxivId(),
                candidate.openAlexId(),
                candidate.url(),
                candidate.abstractText(),
                candidate.source(),
                candidate.sourceQuery(),
                score,
                bibtex(candidate)
        );
    }

    private double score(LiteratureCandidate candidate, Set<String> queryTokens) {
        String haystack = (nullToEmpty(candidate.title()) + " " + nullToEmpty(candidate.abstractText()) + " " + String.join(" ", safeList(candidate.fieldsOfStudy())))
                .toLowerCase(Locale.ROOT);
        long hits = queryTokens.stream().filter(token -> haystack.contains(token.toLowerCase(Locale.ROOT))).count();
        double overlap = queryTokens.isEmpty() ? 0 : (double) hits / queryTokens.size();
        double score = overlap * 0.72;
        if (candidate.citationCount() != null && candidate.citationCount() > 0) {
            score += Math.min(0.10, Math.log10(candidate.citationCount() + 1) / 50.0);
        }
        int currentYear = Year.now().getValue();
        if (candidate.year() != null) {
            if (candidate.year() >= currentYear - 5) score += 0.10;
            else if (candidate.year() >= currentYear - 10) score += 0.05;
        }
        if (StringUtils.hasText(candidate.doi()) || StringUtils.hasText(candidate.arxivId())) score += 0.04;
        if (StringUtils.hasText(candidate.abstractText())) score += 0.04;
        return Math.max(0, Math.min(1.0, score));
    }

    private String bibtex(LiteratureCandidate candidate) {
        String key = bibKey(candidate);
        StringBuilder bib = new StringBuilder();
        bib.append("@article{").append(key).append(",\n")
                .append("  title={").append(escapeBib(candidate.title())).append("},\n");
        if (candidate.authors() != null && !candidate.authors().isEmpty()) {
            bib.append("  author={").append(escapeBib(String.join(" and ", candidate.authors()))).append("},\n");
        }
        if (candidate.year() != null) bib.append("  year={").append(candidate.year()).append("},\n");
        if (StringUtils.hasText(candidate.venue())) bib.append("  journal={").append(escapeBib(candidate.venue())).append("},\n");
        if (StringUtils.hasText(candidate.doi())) bib.append("  doi={").append(escapeBib(normalizeDoi(candidate.doi()))).append("},\n");
        if (StringUtils.hasText(candidate.url())) bib.append("  url={").append(escapeBib(candidate.url())).append("},\n");
        bib.append("  note={Retrieved by Yanban Agent; verify before submission}\n")
                .append("}\n");
        return bib.toString();
    }

    private String bibKey(LiteratureCandidate candidate) {
        String author = "paper";
        if (candidate.authors() != null && !candidate.authors().isEmpty()) {
            author = candidate.authors().get(0).replaceAll("[^A-Za-z]", "");
            if (author.isBlank()) author = "paper";
        }
        String titleToken = firstToken(candidate.title());
        String year = candidate.year() == null ? "nd" : String.valueOf(candidate.year());
        return (author + year + titleToken).replaceAll("[^A-Za-z0-9]", "");
    }

    private String firstToken(String title) {
        if (!StringUtils.hasText(title)) return "ref";
        for (String token : TOKEN_SPLIT.split(title.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 4) return token;
        }
        return "ref";
    }

    private String escapeBib(String value) {
        return nullToEmpty(value).replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}");
    }

    private Set<String> queryTokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(query.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 2) tokens.add(token);
        }
        return tokens;
    }

    private String identityKey(LiteratureCandidate candidate) {
        if (StringUtils.hasText(candidate.doi())) return "doi:" + normalizeDoi(candidate.doi());
        if (StringUtils.hasText(candidate.arxivId())) return "arxiv:" + candidate.arxivId();
        if (StringUtils.hasText(candidate.openAlexId())) return "openalex:" + candidate.openAlexId();
        if (StringUtils.hasText(candidate.s2Id())) return "s2:" + candidate.s2Id();
        return "title:" + titleHash(candidate.title());
    }

    private String titleHash(String title) {
        try {
            String normalized = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsHan}]", "");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash title", ex);
        }
    }

    private String normalizeDoi(String doi) {
        if (doi == null) return null;
        return doi.trim().toLowerCase(Locale.ROOT).replace("https://doi.org/", "").replace("http://doi.org/", "");
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.replaceAll("\\s+", " ").trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    public record AdHocLiteratureSearchResult(String query, List<AdHocLiteratureItem> items, int rawCandidateCount,
                                              int uniqueCandidateCount, int sourceAttempts, List<String> sourceFailures) {
    }

    public record AdHocLiteratureItem(String title, List<String> authors, Integer year, String venue, String doi,
                                      String arxivId, String openAlexId, String url, String abstractText, String source,
                                      String sourceQuery, double score, String bibtex) {
    }
}
