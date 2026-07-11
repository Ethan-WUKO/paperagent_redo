package com.yanban.paper.literature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class LiteratureCardCatalogService {

    private final LiteratureCardRepository cards;
    private final ObjectMapper objectMapper;
    private final LiteratureCardIndexService indexService;

    public LiteratureCardCatalogService(LiteratureCardRepository cards,
                                        ObjectMapper objectMapper,
                                        LiteratureCardIndexService indexService) {
        this.cards = cards;
        this.objectMapper = objectMapper;
        this.indexService = indexService;
    }

    public LiteratureCard upsertCard(LiteratureCandidate candidate) {
        LiteratureCard card = findExisting(candidate)
                .orElseGet(() -> new LiteratureCard(titleHash(candidate.title()), candidate.title()));
        enrich(card, candidate);
        LiteratureCard saved = cards.save(card);
        indexService.index(saved);
        return saved;
    }

    private Optional<LiteratureCard> findExisting(LiteratureCandidate candidate) {
        if (notBlank(candidate.doi())) {
            Optional<LiteratureCard> hit = cards.findByDoi(normalizeDoi(candidate.doi()));
            if (hit.isPresent()) {
                return hit;
            }
        }
        if (notBlank(candidate.arxivId())) {
            Optional<LiteratureCard> hit = cards.findByArxivId(candidate.arxivId());
            if (hit.isPresent()) {
                return hit;
            }
        }
        if (notBlank(candidate.openAlexId())) {
            Optional<LiteratureCard> hit = cards.findByOpenAlexId(candidate.openAlexId());
            if (hit.isPresent()) {
                return hit;
            }
        }
        if (notBlank(candidate.s2Id())) {
            Optional<LiteratureCard> hit = cards.findByS2Id(candidate.s2Id());
            if (hit.isPresent()) {
                return hit;
            }
        }
        return cards.findFirstByTitleHash(titleHash(candidate.title()));
    }

    private void enrich(LiteratureCard card, LiteratureCandidate candidate) {
        card.setDoi(firstNonBlank(card.getDoi(), normalizeDoi(candidate.doi())));
        card.setArxivId(firstNonBlank(card.getArxivId(), candidate.arxivId()));
        card.setOpenAlexId(firstNonBlank(card.getOpenAlexId(), candidate.openAlexId()));
        card.setS2Id(firstNonBlank(card.getS2Id(), candidate.s2Id()));
        card.setTitle(firstNonBlank(card.getTitle(), candidate.title()));
        card.setTitleHash(titleHash(card.getTitle()));
        card.setAuthors(toJson(candidate.authors()));
        card.setPublicationYear(candidate.year());
        card.setVenue(candidate.venue());
        card.setAbstractText(candidate.abstractText());
        card.setUrl(candidate.url());
        card.setPdfUrl(candidate.pdfUrl());
        card.setCitationCount(candidate.citationCount());
        card.setReferencedWorksJson(toJson(candidate.referencedWorks()));
        card.setFieldsOfStudyJson(toJson(candidate.fieldsOfStudy()));
        card.setSourcesJson(mergeSource(card.getSourcesJson(), candidate.source()));
        if (card.getAnalysisJson() == null || card.getAnalysisJson().isBlank()) {
            card.setAnalysisJson(toJson(Map.of(
                    "claim", firstSentence(candidate.abstractText()),
                    "tasks", safeList(candidate.fieldsOfStudy()),
                    "methods", keywordHits(candidate.abstractText()),
                    "limitations", List.of(),
                    "evidenceSource", "retrieved-abstract-rule-based"
            )));
            card.setAnalyzedAt(Instant.now());
            card.setAnalysisModelVersion("rule-based-l3c-v1");
        }
        card.setFetchedAt(Instant.now());
    }

    private String titleHash(String title) {
        try {
            String normalized = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsHan}]", "");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash title", ex);
        }
    }

    private String normalizeDoi(String doi) {
        if (doi == null) {
            return null;
        }
        return doi.trim().toLowerCase(Locale.ROOT).replace("https://doi.org/", "").replace("http://doi.org/", "");
    }

    private String mergeSource(String existingJson, String source) {
        Set<String> values = new LinkedHashSet<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                objectMapper.readTree(existingJson).forEach(node -> values.add(node.asText()));
            } catch (Exception ignored) {
                values.add(existingJson);
            }
        }
        if (source != null && !source.isBlank()) {
            values.add(source);
        }
        return toJson(values);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private String firstNonBlank(String current, String incoming) {
        return notBlank(current) ? current : incoming;
    }

    private String firstSentence(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        int dot = normalized.indexOf('.');
        return dot > 20 ? normalized.substring(0, dot + 1) : normalized;
    }

    private List<String> keywordHits(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (String keyword : List.of("retrieval", "generation", "ranking", "classification", "optimization", "transformer", "graph", "contrastive")) {
            if (lower.contains(keyword)) {
                hits.add(keyword);
            }
        }
        return hits;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
