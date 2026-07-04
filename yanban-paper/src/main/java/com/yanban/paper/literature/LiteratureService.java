package com.yanban.paper.literature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.PaperTaskLiterature;
import com.yanban.paper.domain.PaperTaskLiteratureRepository;
import com.yanban.paper.service.PaperStorageService;
import com.yanban.paper.service.ResearchProfileResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiteratureService {

    private final List<LiteratureSource> sources;
    private final LiteratureCardRepository cards;
    private final PaperTaskLiteratureRepository taskLiterature;
    private final PaperTaskAnalysisRepository analyses;
    private final PaperTaskArtifactRepository artifacts;
    private final PaperTaskRepository tasks;
    private final LiteratureQueryPlanner queryPlanner;
    private final LiteratureCardAnalysisService cardAnalysisService;
    private final LiteratureRerankService rerankService;
    private final PaperStorageService storageService;
    private final ObjectMapper objectMapper;

    public LiteratureService(List<LiteratureSource> sources,
                             LiteratureCardRepository cards,
                             PaperTaskLiteratureRepository taskLiterature,
                             PaperTaskAnalysisRepository analyses,
                             PaperTaskArtifactRepository artifacts,
                             PaperTaskRepository tasks,
                             LiteratureQueryPlanner queryPlanner,
                             LiteratureCardAnalysisService cardAnalysisService,
                             LiteratureRerankService rerankService,
                             PaperStorageService storageService,
                             ObjectMapper objectMapper) {
        this.sources = sources;
        this.cards = cards;
        this.taskLiterature = taskLiterature;
        this.analyses = analyses;
        this.artifacts = artifacts;
        this.tasks = tasks;
        this.queryPlanner = queryPlanner;
        this.cardAnalysisService = cardAnalysisService;
        this.rerankService = rerankService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<LiteratureSearchResult> retrieveForTask(Long taskId, ResearchProfileResult profile, int perQueryLimit, int minSelectionLimit, int selectionLimit) {
        PaperTask task = tasks.findById(taskId).orElse(null);
        Map<String, SlotQuery> slotQueries = slotQueries(taskId);
        List<String> queries = slotQueries.isEmpty()
                ? queryPlanner.planQueries(task == null ? "" : task.getTitle(), task == null ? "en" : task.getTargetLanguage(), profile, 12)
                : new ArrayList<>(slotQueries.keySet());
        Map<String, LiteratureCandidate> unique = new LinkedHashMap<>();
        List<LiteratureCandidate> rawCandidates = new ArrayList<>();
        List<Map<String, Object>> sourceAttemptDetails = new ArrayList<>();
        int sourceAttempts = 0;
        int sourceFailures = 0;
        int rawCandidateCount = 0;
        for (String query : queries) {
            for (LiteratureSource source : sources) {
                sourceAttempts++;
                List<LiteratureCandidate> candidates;
                String failureMessage = null;
                try {
                    candidates = source.search(query, perQueryLimit);
                } catch (Exception ex) {
                    sourceFailures++;
                    failureMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    candidates = List.of();
                }
                rawCandidateCount += candidates.size();
                sourceAttemptDetails.add(Map.of(
                        "query", query,
                        "source", source.name(),
                        "returned", candidates.size(),
                        "failed", failureMessage != null,
                        "failureMessage", failureMessage == null ? "" : failureMessage
                ));
                rawCandidates.addAll(candidates);
                for (LiteratureCandidate candidate : candidates) {
                    if (candidate.title() == null || candidate.title().isBlank()) continue;
                    unique.putIfAbsent(identityKey(candidate), candidate);
                }
            }
        }

        List<LiteratureSearchResult> ranked = unique.values().stream()
                .map(candidate -> {
                    SlotQuery slot = slotQueries.get(candidate.sourceQuery());
                    return toResult(taskId, candidate, score(candidate, profile, slot, taskCoreTerms(task, profile)), slot);
                })
                .sorted(Comparator.comparingDouble(LiteratureSearchResult::relevanceScore).reversed())
                .toList();

        cardAnalysisService.analyzeTopCandidates(ranked);
        ranked = rerankWithCardAnalysis(refreshRankedCards(ranked), slotQueries);
        LiteratureRerankService.RerankResult llmRerank = rerankService.rerank(task, profile, ranked, minSelectionLimit, selectionLimit);
        Set<Long> selectedCardIds = llmRerank.selectedCardIds().isEmpty()
                ? selectedCardIds(ranked, selectionLimit)
                : balancedSelectedCardIds(ranked, llmRerank.selectedCardIds(), minSelectionLimit, selectionLimit);
        List<LiteratureSearchResult> selected = new ArrayList<>();
        for (LiteratureSearchResult result : ranked) {
            boolean isSelected = selectedCardIds.contains(result.card().getId());
            PaperTaskLiterature relation = taskLiterature.findByTaskIdAndCardId(taskId, result.card().getId())
                    .orElseGet(() -> new PaperTaskLiterature(taskId, result.card().getId()));
            relation.setRelevanceScore(result.relevanceScore());
            relation.setNarrativeRole(result.narrativeRole());
            relation.setLadderNode(result.ladderNode());
            relation.setSelected(isSelected);
            relation.setSourceQuery(result.sourceQuery());
            taskLiterature.save(relation);
            if (isSelected) {
                selected.add(new LiteratureSearchResult(result.card(), result.relevanceScore(), result.narrativeRole(), result.ladderNode(), true, result.sourceQuery()));
            }
        }
        writeConceptLadder(taskId, profile, selected, queries, sourceAttempts, sourceFailures, rawCandidateCount, unique.size());
        writeRetrievalArtifacts(task, taskId, profile, queries, sourceAttemptDetails, rawCandidates, unique, ranked, selected);
        return selected;
    }

    private void writeRetrievalArtifacts(PaperTask task, Long taskId, ResearchProfileResult profile, List<String> queries,
                                         List<Map<String, Object>> sourceAttemptDetails, List<LiteratureCandidate> rawCandidates,
                                         Map<String, LiteratureCandidate> unique, List<LiteratureSearchResult> ranked,
                                         List<LiteratureSearchResult> selected) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("paperTitle", task == null ? "" : task.getTitle());
            payload.put("generatedAt", Instant.now().toString());
            payload.put("researchProfile", profile);
            payload.put("queries", queries);
            payload.put("sourceAttempts", sourceAttemptDetails);
            payload.put("rawCandidateCount", rawCandidates.size());
            payload.put("uniqueCandidateCount", unique.size());
            payload.put("rankedCandidateCount", ranked.size());
            payload.put("selectedCandidateCount", selected.size());
            payload.put("rawCandidates", rawCandidates.stream().map(this::candidateMap).toList());
            payload.put("uniqueCandidates", unique.values().stream().map(this::candidateMap).toList());
            payload.put("rankedCandidates", ranked.stream().map(this::resultMap).toList());
            payload.put("selectedCandidates", selected.stream().map(this::resultMap).toList());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            saveArtifact(task, taskId, "retrieved_literature_json", "retrieved-literature.json", json, "application/json; charset=UTF-8",
                    Map.of("rawCandidateCount", rawCandidates.size(), "uniqueCandidateCount", unique.size(), "selectedCandidateCount", selected.size()));
            saveArtifact(task, taskId, "retrieved_literature_md", "retrieved-literature.md", buildRetrievalMarkdown(payload, rawCandidates, ranked, selected), "text/markdown; charset=UTF-8",
                    Map.of("rawCandidateCount", rawCandidates.size(), "uniqueCandidateCount", unique.size(), "selectedCandidateCount", selected.size()));
        } catch (Exception ignored) {
            // Retrieval artifacts are diagnostic only; never fail the paper task because diagnostics cannot be written.
        }
    }

    private void saveArtifact(PaperTask task, Long taskId, String type, String filename, String content, String contentType, Map<String, Object> metadata) throws JsonProcessingException {
        Long userId = task == null ? 0L : task.getUserId();
        int version = artifacts.findFirstByTaskIdAndTypeOrderByVersionDesc(taskId, type)
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);
        String objectKey = storageService.storeArtifact(userId, type, filename, content.getBytes(StandardCharsets.UTF_8), contentType);
        PaperTaskArtifact artifact = new PaperTaskArtifact(taskId, type, objectKey, version);
        Map<String, Object> meta = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        meta.put("filename", filename);
        meta.put("size", content.getBytes(StandardCharsets.UTF_8).length);
        artifact.setMetadataJson(objectMapper.writeValueAsString(meta));
        artifacts.save(artifact);
    }

    private String buildRetrievalMarkdown(Map<String, Object> payload, List<LiteratureCandidate> rawCandidates, List<LiteratureSearchResult> ranked, List<LiteratureSearchResult> selected) {
        StringBuilder md = new StringBuilder();
        md.append("# Retrieved Literature Diagnostics\n\n")
                .append("- Task ID: ").append(payload.get("taskId")).append("\n")
                .append("- Paper title: ").append(payload.get("paperTitle")).append("\n")
                .append("- Generated at: ").append(payload.get("generatedAt")).append("\n")
                .append("- Raw candidates: ").append(payload.get("rawCandidateCount")).append("\n")
                .append("- Unique candidates: ").append(payload.get("uniqueCandidateCount")).append("\n")
                .append("- Selected candidates: ").append(payload.get("selectedCandidateCount")).append("\n\n");
        md.append("## Queries\n\n");
        Object queryObject = payload.get("queries");
        if (queryObject instanceof List<?> queryList) {
            for (Object query : queryList) md.append("- `").append(query).append("`\n");
        }
        md.append("\n## Selected Candidates\n\n");
        appendResults(md, selected);
        md.append("\n## Ranked Unique Candidates (deduplicated by DOI/arXiv/OpenAlex/title)\n\n");
        appendResults(md, ranked.stream().limit(50).toList());
        md.append("\n## Raw Candidates (not deduplicated, first 50)\n\n");
        appendRawCandidates(md, rawCandidates.stream().limit(50).toList());
        md.append("\n## Source Attempts\n\n");
        Object attempts = payload.get("sourceAttempts");
        if (attempts instanceof List<?> attemptList) {
            for (Object attempt : attemptList) md.append("- ").append(attempt).append("\n");
        }
        return md.toString();
    }

    private void appendRawCandidates(StringBuilder md, List<LiteratureCandidate> candidates) {
        if (candidates.isEmpty()) {
            md.append("No candidates.\n");
            return;
        }
        int index = 1;
        for (LiteratureCandidate candidate : candidates) {
            md.append(index++).append(". **").append(candidate.title()).append("**")
                    .append(candidate.year() == null ? "" : " (" + candidate.year() + ")")
                    .append("\n")
                    .append("   - source: ").append(candidate.source()).append("; query: `").append(candidate.sourceQuery() == null ? "" : candidate.sourceQuery()).append("`\n")
                    .append("   - DOI: ").append(candidate.doi() == null ? "" : candidate.doi()).append("; OpenAlex: ").append(candidate.openAlexId() == null ? "" : candidate.openAlexId()).append("\n\n");
        }
    }

    private void appendResults(StringBuilder md, List<LiteratureSearchResult> results) {
        if (results.isEmpty()) {
            md.append("No candidates.\n");
            return;
        }
        int index = 1;
        for (LiteratureSearchResult result : results) {
            LiteratureCard card = result.card();
            md.append(index++).append(". **").append(card.getTitle()).append("**")
                    .append(card.getPublicationYear() == null ? "" : " (" + card.getPublicationYear() + ")")
                    .append("\n")
                    .append("   - Score: ").append(String.format(Locale.ROOT, "%.3f", result.relevanceScore()))
                    .append("; selected: ").append(result.selected())
                    .append("; source query: `").append(result.sourceQuery() == null ? "" : result.sourceQuery()).append("`\n")
                    .append("   - Venue: ").append(card.getVenue() == null ? "" : card.getVenue()).append("\n")
                    .append("   - DOI: ").append(card.getDoi() == null ? "" : card.getDoi()).append("; URL: ").append(card.getUrl() == null ? "" : card.getUrl()).append("\n")
                    .append("   - Abstract: ").append(shorten(card.getAbstractText(), 500)).append("\n\n");
        }
    }

    private Map<String, Object> candidateMap(LiteratureCandidate candidate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("source", candidate.source());
        map.put("sourceQuery", candidate.sourceQuery());
        map.put("title", candidate.title());
        map.put("authors", safeList(candidate.authors()));
        map.put("year", candidate.year());
        map.put("venue", candidate.venue());
        map.put("doi", candidate.doi());
        map.put("arxivId", candidate.arxivId());
        map.put("openAlexId", candidate.openAlexId());
        map.put("url", candidate.url());
        map.put("pdfUrl", candidate.pdfUrl());
        map.put("citationCount", candidate.citationCount());
        map.put("fieldsOfStudy", safeList(candidate.fieldsOfStudy()));
        map.put("abstractText", candidate.abstractText());
        map.put("identityKey", identityKey(candidate));
        return map;
    }

    private Map<String, Object> resultMap(LiteratureSearchResult result) {
        LiteratureCard card = result.card();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cardId", card.getId());
        map.put("selected", result.selected());
        map.put("score", result.relevanceScore());
        map.put("narrativeRole", result.narrativeRole());
        map.put("ladderNode", result.ladderNode());
        map.put("sourceQuery", result.sourceQuery());
        map.put("title", card.getTitle());
        map.put("authors", card.getAuthors());
        map.put("year", card.getPublicationYear());
        map.put("venue", card.getVenue());
        map.put("doi", card.getDoi());
        map.put("arxivId", card.getArxivId());
        map.put("openAlexId", card.getOpenAlexId());
        map.put("url", card.getUrl());
        map.put("pdfUrl", card.getPdfUrl());
        map.put("citationCount", card.getCitationCount());
        map.put("fieldsOfStudy", card.getFieldsOfStudyJson());
        map.put("abstractText", card.getAbstractText());
        return map;
    }

    public List<String> buildQueries(ResearchProfileResult profile) {
        Set<String> queries = new LinkedHashSet<>();
        addIfNotBlank(queries, profile.problem());
        addIfNotBlank(queries, profile.method());
        profile.tasks().forEach(item -> addIfNotBlank(queries, item));
        profile.keywords().forEach(item -> addIfNotBlank(queries, item));
        if (!profile.method().isBlank() && !profile.problem().isBlank()) {
            queries.add(profile.method() + " " + profile.problem());
        }
        return queries.stream().limit(8).toList();
    }

    private LiteratureSearchResult toResult(Long taskId, LiteratureCandidate candidate, double score, SlotQuery slot) {
        LiteratureCard card = upsertCard(candidate);
        String ladderNode = slot == null ? "general" : slot.id();
        String role = slot == null ? narrativeRole(score) : normalizedCategory(slot.category(), slot.query());
        return new LiteratureSearchResult(card, score, safeDbValue(role, 32), ladderNode, false, candidate.sourceQuery());
    }

    private String safeDbValue(String value, int maxLength) {
        if (value == null || value.isBlank()) return "general";
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private LiteratureCard upsertCard(LiteratureCandidate candidate) {
        LiteratureCard card = findExisting(candidate).orElseGet(() -> new LiteratureCard(titleHash(candidate.title()), candidate.title()));
        enrich(card, candidate);
        return cards.save(card);
    }

    private Optional<LiteratureCard> findExisting(LiteratureCandidate candidate) {
        if (notBlank(candidate.doi())) {
            Optional<LiteratureCard> hit = cards.findByDoi(normalizeDoi(candidate.doi()));
            if (hit.isPresent()) return hit;
        }
        if (notBlank(candidate.arxivId())) {
            Optional<LiteratureCard> hit = cards.findByArxivId(candidate.arxivId());
            if (hit.isPresent()) return hit;
        }
        if (notBlank(candidate.openAlexId())) {
            Optional<LiteratureCard> hit = cards.findByOpenAlexId(candidate.openAlexId());
            if (hit.isPresent()) return hit;
        }
        if (notBlank(candidate.s2Id())) {
            Optional<LiteratureCard> hit = cards.findByS2Id(candidate.s2Id());
            if (hit.isPresent()) return hit;
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

    private double score(LiteratureCandidate candidate, ResearchProfileResult profile, SlotQuery slot, Set<String> taskCoreTerms) {
        String title = nullToEmpty(candidate.title()).toLowerCase(Locale.ROOT);
        String haystack = (candidate.title() + " " + nullToEmpty(candidate.abstractText()) + " " + String.join(" ", safeList(candidate.fieldsOfStudy())))
                .toLowerCase(Locale.ROOT);
        double score = 0.0;
        score += tokenOverlap(haystack, profile.keywords()) * 0.20;
        score += tokenOverlap(haystack, profile.tasks()) * 0.10;
        score += phraseContains(haystack, profile.problem()) ? 0.08 : 0;
        score += phraseContains(haystack, profile.method()) ? 0.08 : 0;
        if (slot != null) {
            Set<String> slotCoreTerms = slot.coreTerms().isEmpty() ? extractCoreTerms(slot.category() + " " + slot.claim() + " " + slot.query()) : new LinkedHashSet<>(slot.coreTerms());
            score += tokenOverlap(haystack, List.of(slot.claim())) * 0.22;
            score += tokenOverlap(haystack, List.of(slot.query())) * 0.20;
            score += coreTermOverlap(haystack, slotCoreTerms) * 0.24;
            score += coreTermOverlap(haystack, taskCoreTerms) * 0.14;
            score += domainBoost(haystack, title, slot);
            if ("OPTIONAL_BACKGROUND".equalsIgnoreCase(slot.citationNeed())) score -= 0.08;
        }
        if (candidate.citationCount() != null) {
            score += Math.min(0.08, Math.log10(candidate.citationCount() + 1) / 60.0);
        }
        if (candidate.year() != null && candidate.year() >= 2020) {
            score += 0.04;
        }
        score -= offTopicPenalty(haystack, slot);
        return Math.max(0, Math.min(1.0, score));
    }

    private Map<String, SlotQuery> slotQueries(Long taskId) {
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        if (analysis == null || analysis.getConceptLadderJson() == null || analysis.getConceptLadderJson().isBlank()) return Map.of();
        Map<String, SlotQuery> result = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(analysis.getConceptLadderJson());
            JsonNode slots = root.path("citationSlots");
            if (!slots.isArray()) return Map.of();
            for (JsonNode slot : slots) {
                String id = slot.path("id").asText("slot");
                String category = slot.path("category").asText("citation-slot");
                String claim = slot.path("claim").asText("");
                JsonNode queries = slot.path("queries");
                if (!queries.isArray()) continue;
                for (JsonNode queryNode : queries) {
                    String query = normalizeQuery(queryNode.asText(""));
                    if (!query.isBlank()) result.putIfAbsent(query, new SlotQuery(id, category, claim, query, slot.path("citationNeed").asText("NEEDS_SUPPORT"), jsonStringList(slot.path("coreTerms"))));
                }
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return result;
    }

    private List<LiteratureSearchResult> rerankWithCardAnalysis(List<LiteratureSearchResult> ranked, Map<String, SlotQuery> slotQueries) {
        return ranked.stream()
                .map(item -> {
                    SlotQuery slot = slotQueries.get(item.sourceQuery());
                    double adjusted = Math.max(0, Math.min(1.0, item.relevanceScore() + analysisAdjustment(item.card(), slot)));
                    return new LiteratureSearchResult(item.card(), adjusted, item.narrativeRole(), item.ladderNode(), item.selected(), item.sourceQuery());
                })
                .sorted(Comparator.comparingDouble(LiteratureSearchResult::relevanceScore).reversed())
                .toList();
    }

    private double analysisAdjustment(LiteratureCard card, SlotQuery slot) {
        if (card == null || card.getAnalysisJson() == null || card.getAnalysisJson().isBlank() || slot == null) return 0;
        String analysisText = card.getAnalysisJson().toLowerCase(Locale.ROOT);
        Set<String> slotTerms = slot.coreTerms().isEmpty() ? extractCoreTerms(slot.category() + " " + slot.claim() + " " + slot.query()) : new LinkedHashSet<>(slot.coreTerms());
        double overlap = coreTermOverlap(analysisText, slotTerms);
        double adjustment = overlap * 0.12;
        if (analysisText.contains("notUseFor") && overlap < 0.15) adjustment -= 0.08;
        if (analysisText.contains("\"strength\":\"HIGH\"") || analysisText.contains("\"strength\": \"HIGH\"")) adjustment += 0.03;
        return adjustment;
    }

    private List<LiteratureSearchResult> refreshRankedCards(List<LiteratureSearchResult> ranked) {
        if (ranked == null || ranked.isEmpty()) return List.of();
        Map<Long, LiteratureCard> refreshed = cards.findAllById(ranked.stream().map(item -> item.card().getId()).toList()).stream()
                .collect(Collectors.toMap(LiteratureCard::getId, item -> item));
        return ranked.stream()
                .map(item -> new LiteratureSearchResult(
                        refreshed.getOrDefault(item.card().getId(), item.card()),
                        item.relevanceScore(),
                        item.narrativeRole(),
                        item.ladderNode(),
                        item.selected(),
                        item.sourceQuery()))
                .toList();
    }

    private Set<Long> balancedSelectedCardIds(List<LiteratureSearchResult> ranked, Set<Long> seedIds, int minSelectionLimit, int selectionLimit) {
        if (ranked == null || ranked.isEmpty() || selectionLimit <= 0) return Set.of();
        double minScore = ranked.stream().filter(item -> item.relevanceScore() >= 0.35).count() >= Math.min(minSelectionLimit, selectionLimit)
                ? 0.35
                : (ranked.stream().anyMatch(item -> item.relevanceScore() >= 0.20) ? 0.20 : 0.0);
        double maxFillScore = ranked.stream().filter(item -> item.relevanceScore() >= 0.45).count() >= Math.min(selectionLimit, ranked.size()) ? 0.45 : minScore;
        Map<String, List<LiteratureSearchResult>> byCategory = ranked.stream()
                .filter(item -> item.relevanceScore() >= minScore)
                .filter(item -> !isWeakGenericCandidate(item, minScore))
                .collect(Collectors.groupingBy(item -> normalizedCategory(item.narrativeRole(), item.sourceQuery()), LinkedHashMap::new, Collectors.toList()));
        int categoryCount = Math.max(1, byCategory.size());
        int targetPerCategory = Math.max(1, (int) Math.ceil((double) selectionLimit / categoryCount));
        int maxPerCategory = Math.max(2, targetPerCategory + 1);
        List<LiteratureSearchResult> selected = new ArrayList<>();
        Set<Long> selectedIds = new LinkedHashSet<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (Map.Entry<String, List<LiteratureSearchResult>> entry : byCategory.entrySet()) {
            for (LiteratureSearchResult result : entry.getValue()) {
                if (selectedIds.size() >= selectionLimit || counts.getOrDefault(entry.getKey(), 0) >= targetPerCategory) break;
                if (isWeakGenericCandidate(result, minScore) || selectedIds.contains(result.card().getId()) || nearDuplicate(result, selected)) continue;
                selected.add(result);
                selectedIds.add(result.card().getId());
                counts.merge(entry.getKey(), 1, Integer::sum);
            }
        }

        Set<Long> preferred = new LinkedHashSet<>(seedIds == null ? Set.of() : seedIds);
        for (LiteratureSearchResult result : ranked) {
            if (selectedIds.size() >= selectionLimit) break;
            if (!preferred.contains(result.card().getId())) continue;
            String category = normalizedCategory(result.narrativeRole(), result.sourceQuery());
            if (counts.getOrDefault(category, 0) >= maxPerCategory) continue;
            if (isWeakGenericCandidate(result, minScore) || selectedIds.contains(result.card().getId()) || result.relevanceScore() < minScore || nearDuplicate(result, selected)) continue;
            selected.add(result);
            selectedIds.add(result.card().getId());
            counts.merge(category, 1, Integer::sum);
        }

        int minimum = Math.min(minSelectionLimit, selectionLimit);
        for (LiteratureSearchResult result : ranked) {
            if (selectedIds.size() >= selectionLimit) break;
            String category = normalizedCategory(result.narrativeRole(), result.sourceQuery());
            if (selectedIds.size() >= minimum && (counts.getOrDefault(category, 0) >= maxPerCategory || result.relevanceScore() < maxFillScore)) continue;
            if (isWeakGenericCandidate(result, minScore) || selectedIds.contains(result.card().getId()) || result.relevanceScore() < minScore || nearDuplicate(result, selected)) continue;
            selected.add(result);
            selectedIds.add(result.card().getId());
            counts.merge(category, 1, Integer::sum);
        }
        return selectedIds;
    }

    private Set<Long> selectedCardIds(List<LiteratureSearchResult> ranked, int selectionLimit) {
        return balancedSelectedCardIds(ranked, Set.of(), selectionLimit, selectionLimit);
    }

    private boolean isWeakGenericCandidate(LiteratureSearchResult result, double minScore) {
        String category = normalizedCategory(result.narrativeRole(), result.sourceQuery());
        String text = ((result.card().getTitle() == null ? "" : result.card().getTitle()) + " "
                + (result.card().getAbstractText() == null ? "" : result.card().getAbstractText()) + " "
                + (result.sourceQuery() == null ? "" : result.sourceQuery())).toLowerCase(Locale.ROOT);
        boolean strongPaperAnchor = text.contains("fda") || text.contains("frequency diverse") || text.contains("polar")
                || text.contains("constant modulus") || text.contains("constant-modulus") || text.contains("constant envelope")
                || text.contains("mainlobe") || text.contains("deceptive jamming") || text.contains("sinr")
                || text.contains("mimo-stap") || text.contains("unimodular");
        boolean hasPaperAnchor = strongPaperAnchor || text.contains("sidelobe");
        boolean broadCategory = category.equals("waveform-diversity") || category.equals("general") || category.contains("background");
        boolean genericTopic = text.contains("communication") || text.contains("dfrc") || text.contains("weather radar")
                || text.contains("wideband waveform") || text.contains("surveillance radar") || text.contains("mmwave hybrid beamforming");
        if (genericTopic && !strongPaperAnchor) return true;
        return broadCategory && !hasPaperAnchor && result.relevanceScore() < Math.max(0.45, minScore);
    }

    private boolean containsCard(List<LiteratureSearchResult> selected, Long cardId) {
        return selected.stream().anyMatch(item -> item.card().getId().equals(cardId));
    }

    private boolean nearDuplicate(LiteratureSearchResult candidate, List<LiteratureSearchResult> selected) {
        Set<String> candidateTitle = tokens(candidate.card().getTitle());
        if (candidateTitle.isEmpty()) return false;
        for (LiteratureSearchResult existing : selected) {
            Set<String> existingTitle = tokens(existing.card().getTitle());
            if (existingTitle.isEmpty()) continue;
            Set<String> intersection = new LinkedHashSet<>(candidateTitle);
            intersection.retainAll(existingTitle);
            Set<String> union = new LinkedHashSet<>(candidateTitle);
            union.addAll(existingTitle);
            double jaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
            if (jaccard >= 0.72) return true;
        }
        return false;
    }

    private String normalizedCategory(String category, String query) {
        String text = ((category == null ? "" : category) + " " + (query == null ? "" : query)).toLowerCase(Locale.ROOT);
        if (text.contains("learning") || text.contains("deep")) return "learning-waveform";
        if (text.contains("semidefinite") || text.contains("sdp") || text.contains("manifold") || text.contains("majorization")) return "optimization-methods";
        if (text.contains("constant") || text.contains("unimodular") || text.contains("sidelobe") || text.contains("modulus")) return "constant-modulus-waveform";
        if (text.contains("polarimetric fda") || text.contains("polarimetric") && text.contains("fda")) return "polarimetric-fda-mimo";
        if (text.contains("polar") || text.contains("polarization")) return "polarimetric-anti-jamming";
        if (text.contains("jamming") || text.contains("interference")) return "anti-jamming";
        if (text.contains("waveform")) return "waveform-diversity";
        return text.isBlank() ? "general" : text.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private double tokenOverlap(String haystack, List<String> terms) {
        Set<String> haystackTokens = tokens(haystack);
        if (haystackTokens.isEmpty() || terms == null || terms.isEmpty()) return 0;
        Set<String> queryTokens = new LinkedHashSet<>();
        for (String term : terms) queryTokens.addAll(tokens(term));
        if (queryTokens.isEmpty()) return 0;
        long matches = queryTokens.stream().filter(haystackTokens::contains).count();
        return (double) matches / queryTokens.size();
    }

    private double domainBoost(String haystack, String title, SlotQuery slot) {
        // Dynamic, task-agnostic boost: exact multi-word technical phrases from the slot are stronger than isolated generic words.
        double boost = 0;
        Set<String> phrases = new LinkedHashSet<>();
        phrases.addAll(technicalPhrases(slot.query()));
        phrases.addAll(technicalPhrases(slot.claim()));
        for (String phrase : phrases) {
            if (haystack.contains(phrase)) boost += 0.04;
            if (title.contains(phrase)) boost += 0.04;
            if (boost >= 0.16) return 0.16;
        }
        return boost;
    }

    private Set<String> technicalPhrases(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", " ").replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) return Set.of();
        List<String> words = List.of(normalized.split(" "));
        Set<String> phrases = new LinkedHashSet<>();
        for (int n : List.of(4, 3, 2)) {
            for (int i = 0; i + n <= words.size(); i++) {
                String phrase = String.join(" ", words.subList(i, i + n));
                if (tokens(phrase).size() >= Math.min(2, n)) phrases.add(phrase);
                if (phrases.size() >= 8) return phrases;
            }
        }
        return phrases;
    }

    private double offTopicPenalty(String haystack, SlotQuery slot) {
        if (slot == null) return 0;
        Set<String> slotTokens = slot.coreTerms().isEmpty()
                ? tokens(slot.category() + " " + slot.claim() + " " + slot.query())
                : new LinkedHashSet<>(slot.coreTerms().stream().map(item -> item.toLowerCase(Locale.ROOT)).toList());
        Set<String> candidateTokens = tokens(haystack);
        if (slotTokens.isEmpty() || candidateTokens.isEmpty()) return 0;
        long matches = slotTokens.stream().filter(candidateTokens::contains).count();
        double overlap = (double) matches / slotTokens.size();
        Set<String> queryTokens = tokens(slot.query());
        long queryMatches = queryTokens.stream().filter(candidateTokens::contains).count();
        double queryOverlap = queryTokens.isEmpty() ? overlap : (double) queryMatches / queryTokens.size();
        if (queryOverlap < 0.25 && overlap < 0.18) return 0.35;
        if (queryOverlap < 0.34 && overlap < 0.25) return 0.18;
        return 0;
    }

    private boolean phraseContains(String haystack, String term) {
        return term != null && !term.isBlank() && haystack.contains(term.toLowerCase(Locale.ROOT));
    }

    private Set<String> tokens(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> stop = Set.of("the", "and", "for", "with", "via", "from", "that", "this", "into", "over", "under", "based", "using", "design", "method", "approach", "study", "analysis", "optimization");
        Set<String> values = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", " ").split("\\s+")) {
            String cleaned = token.replaceAll("^-+|-+$", "");
            if (cleaned.length() >= 3 && !stop.contains(cleaned)) values.add(cleaned);
        }
        return values;
    }

    private List<String> jsonStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").trim();
            if (!value.isBlank()) values.add(value);
        });
        return values;
    }

    private Set<String> taskCoreTerms(PaperTask task, ResearchProfileResult profile) {
        StringBuilder builder = new StringBuilder();
        if (task != null) builder.append(task.getTitle()).append(' ');
        if (profile != null) {
            builder.append(profile.problem()).append(' ').append(profile.method()).append(' ')
                    .append(String.join(" ", safeList(profile.keywords()))).append(' ')
                    .append(String.join(" ", safeList(profile.tasks()))).append(' ')
                    .append(String.join(" ", safeList(profile.contributions())));
        }
        return extractCoreTerms(builder.toString());
    }

    private Set<String> extractCoreTerms(String text) {
        Set<String> stop = Set.of("the", "and", "for", "with", "that", "this", "using", "based", "method", "methods", "design", "study", "analysis", "performance", "existing", "work", "works", "need", "support", "optimization", "approach", "system", "systems");
        Set<String> terms = new LinkedHashSet<>();
        for (String token : (text == null ? "" : text).replaceAll("[^A-Za-z0-9-]+", " ").split("\\s+")) {
            String cleaned = token.replaceAll("^-+|-+$", "").trim();
            if (cleaned.length() < 3) continue;
            String lower = cleaned.toLowerCase(Locale.ROOT);
            if (!stop.contains(lower)) terms.add(lower);
            if (terms.size() >= 30) break;
        }
        return terms;
    }

    private double coreTermOverlap(String haystack, Set<String> coreTerms) {
        if (coreTerms == null || coreTerms.isEmpty()) return 0;
        Set<String> candidateTokens = tokens(haystack);
        if (candidateTokens.isEmpty()) return 0;
        long matches = coreTerms.stream().map(term -> term.toLowerCase(Locale.ROOT)).filter(candidateTokens::contains).count();
        return (double) matches / coreTerms.size();
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.replace('“', ' ').replace('”', ' ').replace('"', ' ')
                .replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean contains(String haystack, String term) {
        return phraseContains(haystack, term);
    }

    private void writeConceptLadder(Long taskId, ResearchProfileResult profile, List<LiteratureSearchResult> selected,
                                    List<String> queries, int sourceAttempts, int sourceFailures,
                                    int rawCandidateCount, int uniqueCandidateCount) {
        Map<String, Object> ladder = existingConceptLadder(taskId);
        ladder.put("problem", profile.problem());
        ladder.put("method", profile.method());
        ladder.put("selectedBySlot", selected.stream()
                .collect(Collectors.groupingBy(
                        item -> item.ladderNode() == null ? "general" : item.ladderNode(),
                        LinkedHashMap::new,
                        Collectors.mapping(this::ladderCard, Collectors.toList())
                )));
        ladder.put("selectedLiterature", selected.stream().map(this::ladderCard).toList());
        ladder.put("retrievalDiagnostics", Map.of(
                "queries", queries,
                "sourceAttempts", sourceAttempts,
                "sourceFailures", sourceFailures,
                "rawCandidateCount", rawCandidateCount,
                "uniqueCandidateCount", uniqueCandidateCount,
                "selectedCount", selected.size()
        ));
        ladder.put("retrievalGeneratedBy", "slot-based-literature-service-v2");
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElseGet(() -> new PaperTaskAnalysis(taskId));
        analysis.setConceptLadderJson(toJson(ladder));
        analyses.save(analysis);
    }

    private Map<String, Object> existingConceptLadder(Long taskId) {
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        if (analysis == null || analysis.getConceptLadderJson() == null || analysis.getConceptLadderJson().isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(analysis.getConceptLadderJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> ladderCard(LiteratureSearchResult result) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("cardId", result.card().getId());
        card.put("title", result.card().getTitle());
        card.put("score", result.relevanceScore());
        card.put("sourceQuery", result.sourceQuery());
        return card;
    }

    private String narrativeRole(double score) {
        return score >= 0.5 ? "advocacy" : "critique";
    }

    private String identityKey(LiteratureCandidate candidate) {
        if (notBlank(candidate.doi())) return "doi:" + normalizeDoi(candidate.doi());
        if (notBlank(candidate.arxivId())) return "arxiv:" + candidate.arxivId();
        if (notBlank(candidate.openAlexId())) return "openalex:" + candidate.openAlexId();
        if (notBlank(candidate.s2Id())) return "s2:" + candidate.s2Id();
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

    private String firstSentence(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        int dot = normalized.indexOf('.');
        return dot > 20 ? normalized.substring(0, dot + 1) : normalized;
    }

    private List<String> keywordHits(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (String keyword : List.of("retrieval", "generation", "ranking", "classification", "optimization", "transformer", "graph", "contrastive")) {
            if (lower.contains(keyword)) hits.add(keyword);
        }
        return hits;
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
        if (source != null && !source.isBlank()) values.add(source);
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

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String shorten(String value, int maxLength) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private void addIfNotBlank(Set<String> values, String value) {
        if (notBlank(value)) values.add(value.trim());
    }

    private record SlotQuery(String id, String category, String claim, String query, String citationNeed, List<String> coreTerms) {
    }
}
