package com.yanban.paper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTaskAnalysis;
import com.yanban.paper.domain.PaperTaskAnalysisRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskLiterature;
import com.yanban.paper.domain.PaperTaskLiteratureRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.latex.LatexBibEntry;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexLintIssue;
import com.yanban.paper.latex.LatexMaskingService;
import com.yanban.paper.latex.LatexSection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperAssembleService {

    private static final Pattern BIB_KEY_PATTERN = Pattern.compile("\\\\(?:cite|citep|citet|parencite|textcite|autocite)\\*?(?:\\s*\\[[^]]*]){0,2}\\s*\\{([^{}]+)}");
    private static final Pattern LABEL_PATTERN = Pattern.compile("\\\\label\\s*\\{([^{}]+)}");
    private static final Pattern REF_PATTERN = Pattern.compile("\\\\(?:ref|eqref|cref|Cref|autoref|pageref)\\*?\\s*\\{([^{}]+)}");
    private static final Pattern COMMENT_LINE_PATTERN = Pattern.compile("(?m)^\\s*%.*$");
    private static final double SUPPLEMENTAL_BIB_MIN_SCORE = 0.0;

    private final PaperTaskRepository tasks;
    private final PaperSectionRepository sections;
    private final PaperTaskArtifactRepository artifacts;
    private final PaperTaskAnalysisRepository analyses;
    private final PaperTaskLiteratureRepository taskLiterature;
    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidenceRepository;
    private final LiteratureCardRepository literatureCards;
    private final PaperStorageService storageService;
    private final LatexMaskingService maskingService;
    private final ObjectMapper objectMapper;

    public PaperAssembleService(PaperTaskRepository tasks,
                                PaperSectionRepository sections,
                                PaperTaskArtifactRepository artifacts,
                                PaperTaskAnalysisRepository analyses,
                                PaperTaskLiteratureRepository taskLiterature,
                                SuggestionRepository suggestions,
                                SuggestionEvidenceRepository evidenceRepository,
                                LiteratureCardRepository literatureCards,
                                PaperStorageService storageService,
                                LatexMaskingService maskingService,
                                ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.sections = sections;
        this.artifacts = artifacts;
        this.analyses = analyses;
        this.taskLiterature = taskLiterature;
        this.suggestions = suggestions;
        this.evidenceRepository = evidenceRepository;
        this.literatureCards = literatureCards;
        this.storageService = storageService;
        this.maskingService = maskingService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaperAssembleResult assemble(Long taskId, LatexDocument document, boolean advancedMode) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        List<PaperSection> storedSections = sections.findByTaskIdOrderByOrderIndexAsc(taskId);
        Map<Integer, PaperSection> sectionByOrder = storedSections.stream()
                .collect(Collectors.toMap(PaperSection::getOrderIndex, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Long, String> polishedTextBySectionId = readPolishedSectionTexts(storedSections);
        String polishedTex = advancedMode ? assembleTex(document, sectionByOrder, polishedTextBySectionId) : "";
        List<Suggestion> taskSuggestions = suggestions.findByTaskIdOrderByCreatedAt(taskId);
        Map<Long, List<LiteratureCard>> evidenceCards = evidenceCards(taskSuggestions);
        int literatureLimit = literatureLimit(task);
        BibBuildResult bibResult = buildSuggestedBib(taskId, taskSuggestions, evidenceCards, document.bibliography(), literatureLimit);
        String suggestedBib = bibResult.allBib();
        String reviewReport = buildReviewReport(task, storedSections, taskSuggestions, evidenceCards, advancedMode, bibResult);
        List<String> lintCodes = advancedMode ? validateAssembledTex(polishedTex, document.bibliography().keySet()).stream()
                .map(LatexLintIssue::code)
                .distinct()
                .toList() : List.of();

        List<Map<String, Object>> artifactResults = new ArrayList<>();
        if (advancedMode) {
            artifactResults.add(saveArtifact(task, "polished_tex", "polished.tex", polishedTex, "application/x-tex; charset=UTF-8", Map.of("advancedMode", true, "lintCodes", lintCodes)));
        }
        artifactResults.add(saveArtifact(task, "suggested_bib", "suggested.bib", suggestedBib, "text/x-bibtex; charset=UTF-8", Map.of("source", "real-literature-cards", "dedup", "none")));
        artifactResults.add(saveArtifact(task, "suggested_bib_novel", "suggested-novel.bib", bibResult.novelBib(), "text/x-bibtex; charset=UTF-8", Map.of("source", "real-literature-cards", "dedup", "uploaded-bib")));
        artifactResults.add(saveArtifact(task, "review_report", "review-report.md", reviewReport, "text/markdown; charset=UTF-8", Map.of("advancedMode", advancedMode, "suggestionCount", taskSuggestions.size())));
        if (advancedMode && !artifactResults.isEmpty()) {
            task.setFinalObjectKey(String.valueOf(artifactResults.get(0).get("objectKey")));
        }
        task.setCurrentStage("ASSEMBLE");
        task.setStatus("COMPLETED");
        tasks.save(task);
        return new PaperAssembleResult(taskId, advancedMode, polishedTex, suggestedBib, reviewReport, artifactResults, lintCodes);
    }

    private Map<Long, String> readPolishedSectionTexts(List<PaperSection> storedSections) {
        Map<Long, String> texts = new LinkedHashMap<>();
        for (PaperSection section : storedSections) {
            if (!"POLISHED".equalsIgnoreCase(section.getPolishStatus())) {
                continue;
            }
            String objectKey = section.getPolishedObjectKey();
            if (objectKey != null && !objectKey.isBlank() && !objectKey.startsWith("memory://")) {
                try {
                    texts.put(section.getId(), new String(storageService.read(objectKey), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // v1 polish service may not have persisted section body yet; fall back to raw LatexSection.
                }
            }
        }
        return texts;
    }

    private String assembleTex(LatexDocument document, Map<Integer, PaperSection> sectionByOrder, Map<Long, String> polishedTextBySectionId) {
        List<LatexSection> docSections = document.sections().stream()
                .sorted(Comparator.comparingInt(LatexSection::orderIndex))
                .toList();
        if (docSections.isEmpty()) {
            return document.preamble();
        }
        StringBuilder builder = new StringBuilder();
        if (document.preamble() != null && !document.preamble().isBlank()) {
            builder.append(document.preamble());
            if (!document.preamble().endsWith("\n")) builder.append('\n');
            builder.append("\\begin{document}\n");
            if (document.frontMatter() != null && !document.frontMatter().isBlank()) {
                builder.append(stripDocumentEnd(document.frontMatter()));
                if (!document.frontMatter().endsWith("\n")) builder.append('\n');
            }
        }
        for (LatexSection section : docSections) {
            PaperSection stored = sectionByOrder.get(section.orderIndex());
            String text = stored == null ? null : polishedTextBySectionId.get(stored.getId());
            if (text == null || text.isBlank()) {
                text = section.rawText();
            }
            text = stripDocumentEnd(text);
            builder.append(text);
            if (!text.endsWith("\n")) builder.append('\n');
        }
        if (document.preamble() != null && !document.preamble().isBlank()) {
            builder.append("\\end{document}\n");
        }
        return builder.toString();
    }

    private String stripDocumentEnd(String text) {
        if (text == null || text.isBlank()) return "";
        return text.replaceAll("(?is)\\\\end\\s*\\{document}\\s*$", "");
    }

    private Map<Long, List<LiteratureCard>> evidenceCards(List<Suggestion> taskSuggestions) {
        List<Long> suggestionIds = taskSuggestions.stream().map(Suggestion::getId).filter(Objects::nonNull).toList();
        if (suggestionIds.isEmpty()) return Map.of();
        List<SuggestionEvidence> evidences = evidenceRepository.findBySuggestionIdIn(suggestionIds);
        Set<Long> cardIds = evidences.stream().map(SuggestionEvidence::getCardId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, LiteratureCard> cardsById = literatureCards.findAllById(cardIds).stream()
                .collect(Collectors.toMap(LiteratureCard::getId, Function.identity()));
        Map<Long, List<LiteratureCard>> result = new LinkedHashMap<>();
        for (SuggestionEvidence evidence : evidences) {
            LiteratureCard card = cardsById.get(evidence.getCardId());
            if (card != null) {
                result.computeIfAbsent(evidence.getSuggestionId(), ignored -> new ArrayList<>()).add(card);
            }
        }
        return result;
    }

    private BibBuildResult buildSuggestedBib(Long taskId, List<Suggestion> taskSuggestions, Map<Long, List<LiteratureCard>> evidenceCards, Map<String, LatexBibEntry> existingBibliography, int literatureLimit) {
        Set<Long> usedCards = new LinkedHashSet<>();
        for (Suggestion suggestion : taskSuggestions) {
            if ("ADVOCACY".equalsIgnoreCase(suggestion.getTrack()) || Boolean.TRUE.equals(suggestion.getApplicable())) {
                evidenceCards.getOrDefault(suggestion.getId(), List.of()).forEach(card -> usedCards.add(card.getId()));
            }
        }
        List<PaperTaskLiterature> selectedLiterature = taskLiterature.findByTaskIdOrderByRelevanceScoreDesc(taskId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getSelected()))
                .filter(item -> item.getRelevanceScore() >= SUPPLEMENTAL_BIB_MIN_SCORE)
                .filter(item -> item.getCardId() != null && !usedCards.contains(item.getCardId()))
                .toList();
        Set<String> usedCategories = new LinkedHashSet<>();
        Map<String, Integer> perCategory = new LinkedHashMap<>();
        for (PaperTaskLiterature item : selectedLiterature) {
            if (usedCards.size() >= literatureLimit) break;
            String category = normalizedLiteratureCategory(item);
            if (usedCategories.add(category)) {
                usedCards.add(item.getCardId());
                perCategory.put(category, perCategory.getOrDefault(category, 0) + 1);
            }
        }
        for (PaperTaskLiterature item : selectedLiterature) {
            if (usedCards.size() >= literatureLimit) break;
            if (usedCards.contains(item.getCardId())) continue;
            String category = normalizedLiteratureCategory(item);
            int count = perCategory.getOrDefault(category, 0);
            if (count >= 2) continue;
            usedCards.add(item.getCardId());
            perCategory.put(category, count + 1);
        }
        for (PaperTaskLiterature item : selectedLiterature) {
            if (usedCards.size() >= literatureLimit) break;
            if (usedCards.contains(item.getCardId())) continue;
            usedCards.add(item.getCardId());
        }
        StringBuilder bib = new StringBuilder();
        StringBuilder novelBib = new StringBuilder();
        Set<String> usedKeys = new LinkedHashSet<>(existingBibliography == null ? Set.of() : existingBibliography.keySet());
        Set<String> existingTitles = existingTitleFingerprints(existingBibliography);
        Set<String> existingDois = existingDois(existingBibliography);
        List<Map<String, Object>> duplicateCards = new ArrayList<>();
        List<Map<String, Object>> novelCards = new ArrayList<>();
        int written = 0;
        for (Long cardId : usedCards) {
            if (written >= literatureLimit) break;
            Optional<LiteratureCard> cardOpt = literatureCards.findById(cardId);
            if (cardOpt.isEmpty()) continue;
            LiteratureCard card = cardOpt.get();
            String key = uniqueBibKey(card, usedKeys);
            usedKeys.add(key);
            written++;
            String entry = bibEntry(key, card);
            bib.append(entry);
            if (alreadyInBibliography(card, existingTitles, existingDois)) {
                duplicateCards.add(cardSummary(card));
            } else {
                novelBib.append(entry);
                novelCards.add(cardSummary(card));
            }
        }
        return new BibBuildResult(bib.toString(), novelBib.toString(), novelCards, duplicateCards, existingBibliography == null ? 0 : existingBibliography.size());
    }

    private String bibEntry(String key, LiteratureCard card) {
        StringBuilder bib = new StringBuilder();
        bib.append("@article{").append(key).append(",\n")
                .append("  title={").append(escapeBib(card.getTitle())).append("},\n")
                .append("  author={").append(escapeBib(authors(card))).append("},\n");
        if (card.getPublicationYear() != null) bib.append("  year={").append(card.getPublicationYear()).append("},\n");
        if (notBlank(card.getVenue())) bib.append("  journal={").append(escapeBib(card.getVenue())).append("},\n");
        if (notBlank(card.getDoi())) bib.append("  doi={").append(escapeBib(card.getDoi())).append("},\n");
        if (notBlank(card.getUrl())) bib.append("  url={").append(escapeBib(card.getUrl())).append("},\n");
        bib.append("  note={Suggested by Yanban Agent; verify before submission}\n")
                .append("}\n\n");
        return bib.toString();
    }

    private boolean alreadyInBibliography(LiteratureCard card, Set<String> existingTitles, Set<String> existingDois) {
        String doi = normalizeDoi(card.getDoi());
        if (notBlank(doi) && existingDois.contains(doi)) return true;
        String title = titleFingerprint(card.getTitle());
        return notBlank(title) && existingTitles.contains(title);
    }

    private Set<String> existingTitleFingerprints(Map<String, LatexBibEntry> bibliography) {
        if (bibliography == null || bibliography.isEmpty()) return Set.of();
        return bibliography.values().stream()
                .map(entry -> entry.fields() == null ? "" : titleFingerprint(entry.fields().get("title")))
                .filter(this::notBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> existingDois(Map<String, LatexBibEntry> bibliography) {
        if (bibliography == null || bibliography.isEmpty()) return Set.of();
        return bibliography.values().stream()
                .map(entry -> entry.fields() == null ? "" : normalizeDoi(entry.fields().get("doi")))
                .filter(this::notBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String titleFingerprint(String title) {
        if (title == null) return "";
        return title.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[{}]", "")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeDoi(String doi) {
        if (doi == null) return "";
        return doi.toLowerCase(java.util.Locale.ROOT)
                .replace("https://doi.org/", "")
                .replace("http://doi.org/", "")
                .trim();
    }

    private Map<String, Object> cardSummary(LiteratureCard card) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cardId", card.getId());
        map.put("title", card.getTitle());
        map.put("year", card.getPublicationYear());
        map.put("doi", card.getDoi());
        return map;
    }

    private record BibBuildResult(String allBib, String novelBib, List<Map<String, Object>> novelCards, List<Map<String, Object>> duplicateCards, int existingBibliographyCount) {}

    private int literatureLimit(PaperTask task) {
        Integer value = task.getLiteratureCount();
        return value == null ? 20 : Math.max(1, Math.min(100, value));
    }

    private String normalizedLiteratureCategory(PaperTaskLiterature item) {
        String text = ((item.getNarrativeRole() == null ? "" : item.getNarrativeRole()) + " "
                + (item.getSourceQuery() == null ? "" : item.getSourceQuery())).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("learning") || text.contains("deep")) return "learning-waveform";
        if (text.contains("semidefinite") || text.contains("sdp") || text.contains("manifold") || text.contains("majorization")) return "optimization-methods";
        if (text.contains("constant") || text.contains("unimodular") || text.contains("sidelobe") || text.contains("modulus")) return "constant-modulus-waveform";
        if (text.contains("polarimetric fda") || (text.contains("polarimetric") && text.contains("fda"))) return "polarimetric-fda-mimo";
        if (text.contains("polar") || text.contains("polarization")) return "polarimetric-anti-jamming";
        if (text.contains("jamming") || text.contains("interference")) return "anti-jamming";
        if (text.contains("waveform")) return "waveform-diversity";
        return text.isBlank() ? "general" : text.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private String buildReviewReport(PaperTask task, List<PaperSection> sections, List<Suggestion> taskSuggestions,
                                     Map<Long, List<LiteratureCard>> evidenceCards, boolean advancedMode, BibBuildResult bibResult) {
        StringBuilder report = new StringBuilder();
        report.append("# Paper Review Report\n\n")
                .append("- Task: ").append(task.getTitle()).append("\n")
                .append("- Mode: ").append(advancedMode ? "advanced" : "basic").append("\n\n")
                .append("## Section Polish Summary\n\n");
        for (PaperSection section : sections) {
            report.append("- ").append(section.getTitle()).append(" (`").append(section.getRole()).append("`): ")
                    .append(section.getPolishStatus() == null ? "NOT_POLISHED" : section.getPolishStatus()).append("\n");
        }
        report.append("\n## Introduction Analysis Diagnostics\n\n");
        appendIntroductionAnalysisDiagnostics(report, task.getId());
        report.append("\n## Introduction Citation Slots\n\n");
        appendCitationSlots(report, task.getId());
        report.append("\n## Retrieval Diagnostics\n\n");
        appendRetrievalDiagnostics(report, task.getId());
        appendSupplementalBibliography(report, task.getId(), evidenceCards, literatureLimit(task));
        appendUploadedBibDedupSummary(report, bibResult);
        report.append("\n## Suggestions\n\n");
        if (taskSuggestions.isEmpty()) {
            report.append("No suggestions generated. Check Retrieval Diagnostics above to distinguish empty query, source failure, or zero selected candidates.\n\n");
        }
        for (Suggestion suggestion : taskSuggestions) {
            report.append("### #").append(suggestion.getId()).append(" ").append(suggestion.getCategory()).append("\n")
                    .append("- Track: ").append(suggestion.getTrack()).append("\n")
                    .append("- Severity: ").append(suggestion.getSeverity()).append("\n")
                    .append("- Applicable: ").append(suggestion.getApplicable()).append("\n")
                    .append("- Statement: ").append(suggestion.getStatement()).append("\n")
                    .append("- Evidence:\n");
            List<LiteratureCard> cards = evidenceCards.getOrDefault(suggestion.getId(), List.of());
            if (cards.isEmpty()) {
                report.append("  - None (not grounded; do not patch into paper)\n");
            } else {
                for (LiteratureCard card : cards) {
                    report.append("  - [card-").append(card.getId()).append("] ").append(card.getTitle())
                            .append(card.getPublicationYear() == null ? "" : " (" + card.getPublicationYear() + ")")
                            .append("\n");
                }
            }
            report.append('\n');
        }
        report.append("## Disclaimer\n\n")
                .append("This report is an AI-assisted self-check and editing aid. It is not a substitute for peer review, advisor feedback, or venue-specific submission checks. Verify every citation, claim, and LaTeX change before submission.\n");
        return report.toString();
    }

    private void appendIntroductionAnalysisDiagnostics(StringBuilder report, Long taskId) {
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        if (analysis == null || analysis.getConceptLadderJson() == null || analysis.getConceptLadderJson().isBlank()) {
            report.append("- No Introduction analysis recorded.\n");
            return;
        }
        try {
            Map<String, Object> ladder = objectMapper.readValue(analysis.getConceptLadderJson(), new TypeReference<Map<String, Object>>() {});
            report.append("- Generated by: ").append(valueOrDefault(ladder, "generatedBy", "unknown")).append("\n")
                    .append("- Degraded: ").append(valueOrDefault(ladder, "degraded", "unknown")).append("\n");
            Object diagnostics = ladder.get("introductionAnalysisDiagnostics");
            if (diagnostics instanceof Map<?, ?> map) {
                report.append("- LLM call mode: ").append(valueOrDefault(map, "llmCallMode", "single-call-or-legacy")).append("\n")
                        .append("- LLM call count: ").append(valueOrDefault(map, "llmCallCount", valueOrDefault(map, "llmAttemptCount", "unknown"))).append("\n")
                        .append("- Max tokens used: ").append(valueOrDefault(map, "maxTokensUsed", "part-specific")).append("\n")
                        .append("- Previous error: ").append(valueOrDefault(map, "previousError", "")).append("\n")
                        .append("- Raw LLM slot count: ").append(valueOrDefault(map, "rawSlotCount", "unknown")).append("\n")
                        .append("- Accepted LLM slot count: ").append(valueOrDefault(map, "acceptedLlmSlotCount", "unknown")).append("\n")
                        .append("- Minimum slot count: ").append(valueOrDefault(map, "minimumSlotCount", "unknown")).append("\n")
                        .append("- Final slot count: ").append(valueOrDefault(map, "finalSlotCount", "unknown")).append("\n")
                        .append("- Fallback-added slot count: ").append(valueOrDefault(map, "fallbackAddedSlotCount", "unknown")).append("\n")
                        .append("- Fallback reason: ").append(valueOrDefault(map, "fallbackReason", "")).append("\n");
                String rawPreview = String.valueOf(valueOrDefault(map, "rawTextPreview", ""));
                if (!rawPreview.isBlank()) {
                    report.append("- Raw LLM response preview:\n\n```json\n")
                            .append(rawPreview)
                            .append("\n```\n");
                }
            } else {
                report.append("- No detailed diagnostics recorded.\n");
            }
        } catch (Exception ex) {
            report.append("- Failed to parse Introduction diagnostics: ").append(ex.getMessage()).append("\n");
        }
    }

    private void appendCitationSlots(StringBuilder report, Long taskId) {
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        if (analysis == null || analysis.getConceptLadderJson() == null || analysis.getConceptLadderJson().isBlank()) {
            report.append("- No Introduction citation-slot analysis recorded.\n");
            return;
        }
        try {
            Map<String, Object> ladder = objectMapper.readValue(analysis.getConceptLadderJson(), new TypeReference<Map<String, Object>>() {});
            Object slots = ladder.get("citationSlots");
            if (!(slots instanceof List<?> list) || list.isEmpty()) {
                report.append("- No citation slots recorded.\n");
                return;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> slot)) continue;
                report.append("- **").append(valueOrDefault(slot, "category", "Citation slot")).append("**: ")
                        .append(valueOrDefault(slot, "claim", "")).append("\n")
                        .append("  - Need: ").append(valueOrDefault(slot, "citationNeed", "NEEDS_SUPPORT")).append("\n")
                        .append("  - Existing citation keys: ").append(valueOrDefault(slot, "existingCitationKeys", List.of())).append("\n")
                        .append("  - Queries: ").append(valueOrDefault(slot, "queries", List.of())).append("\n");
            }
        } catch (Exception ex) {
            report.append("- Failed to parse citation slots: ").append(ex.getMessage()).append("\n");
        }
    }

    private void appendUploadedBibDedupSummary(StringBuilder report, BibBuildResult bibResult) {
        report.append("\n## Uploaded Bibliography Deduplication\n\n")
                .append("- Uploaded bibliography entries parsed: ").append(bibResult.existingBibliographyCount()).append("\n")
                .append("- Selected recommendations before uploaded-bib dedup: ").append(bibResult.novelCards().size() + bibResult.duplicateCards().size()).append("\n")
                .append("- Novel recommendations after uploaded-bib dedup: ").append(bibResult.novelCards().size()).append("\n")
                .append("- Already present in uploaded bibliography: ").append(bibResult.duplicateCards().size()).append("\n\n");
        if (!bibResult.duplicateCards().isEmpty()) {
            report.append("Already-present recommendations are kept in `suggested.bib` for transparency, but excluded from `suggested-novel.bib`.\n\n");
            for (Map<String, Object> item : bibResult.duplicateCards()) {
                report.append("- [card-").append(item.get("cardId")).append("] ").append(item.get("title"))
                        .append(item.get("year") == null ? "" : " (" + item.get("year") + ")")
                        .append("\n");
            }
            report.append('\n');
        }
    }

    private void appendSupplementalBibliography(StringBuilder report, Long taskId, Map<Long, List<LiteratureCard>> evidenceCards, int literatureLimit) {
        Set<Long> evidenceIds = evidenceCards.values().stream()
                .flatMap(List::stream)
                .map(LiteratureCard::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PaperTaskLiterature> supplemental = taskLiterature.findByTaskIdOrderByRelevanceScoreDesc(taskId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getSelected()))
                .filter(item -> item.getCardId() != null && !evidenceIds.contains(item.getCardId()))
                .filter(item -> item.getRelevanceScore() >= SUPPLEMENTAL_BIB_MIN_SCORE)
                .limit(literatureLimit)
                .toList();
        if (supplemental.isEmpty()) return;
        Map<Long, LiteratureCard> cardsById = literatureCards.findAllById(supplemental.stream().map(PaperTaskLiterature::getCardId).toList()).stream()
                .collect(Collectors.toMap(LiteratureCard::getId, Function.identity()));
        report.append("\n## Supplemental Bibliography Candidates\n\n")
                .append("These are selected real retrieval candidates added to `suggested.bib` as weak recommendations when grounded Gap suggestions are sparse. Verify relevance before citing.\n\n");
        for (PaperTaskLiterature item : supplemental) {
            LiteratureCard card = cardsById.get(item.getCardId());
            if (card == null) continue;
            report.append("- [card-").append(card.getId()).append("] ").append(card.getTitle())
                    .append(card.getPublicationYear() == null ? "" : " (" + card.getPublicationYear() + ")")
                    .append(" — score ").append(String.format(java.util.Locale.ROOT, "%.2f", item.getRelevanceScore()))
                    .append(", query: `").append(item.getSourceQuery() == null ? "" : item.getSourceQuery()).append("`\n");
        }
        report.append('\n');
    }

    private void appendRetrievalDiagnostics(StringBuilder report, Long taskId) {
        PaperTaskAnalysis analysis = analyses.findByTaskId(taskId).orElse(null);
        if (analysis == null || analysis.getConceptLadderJson() == null || analysis.getConceptLadderJson().isBlank()) {
            report.append("- No retrieval diagnostics recorded.\n");
            return;
        }
        try {
            Map<String, Object> ladder = objectMapper.readValue(analysis.getConceptLadderJson(), new TypeReference<Map<String, Object>>() {});
            Object diagnostics = ladder.get("retrievalDiagnostics");
            if (!(diagnostics instanceof Map<?, ?> map)) {
                report.append("- No retrieval diagnostics recorded.\n");
                return;
            }
            report.append("- Queries: ").append(valueOrDefault(map, "queries", List.of())).append("\n")
                    .append("- Source attempts: ").append(valueOrDefault(map, "sourceAttempts", "unknown")).append("\n")
                    .append("- Source failures: ").append(valueOrDefault(map, "sourceFailures", "unknown")).append("\n")
                    .append("- Raw candidates: ").append(valueOrDefault(map, "rawCandidateCount", "unknown")).append("\n")
                    .append("- Unique candidates: ").append(valueOrDefault(map, "uniqueCandidateCount", "unknown")).append("\n")
                    .append("- Selected candidates: ").append(valueOrDefault(map, "selectedCount", "unknown")).append("\n");
        } catch (Exception ex) {
            report.append("- Failed to parse retrieval diagnostics: ").append(ex.getMessage()).append("\n");
        }
    }

    private Object valueOrDefault(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private List<LatexLintIssue> validateAssembledTex(String tex, Set<String> existingBibKeys) {
        List<LatexLintIssue> issues = new ArrayList<>(maskingService.lint(tex));
        Set<String> knownKeys = existingBibKeys == null ? Set.of() : existingBibKeys;
        String activeTex = COMMENT_LINE_PATTERN.matcher(tex == null ? "" : tex).replaceAll("");
        Set<String> labels = new LinkedHashSet<>();
        Matcher labelMatcher = LABEL_PATTERN.matcher(activeTex);
        while (labelMatcher.find()) {
            labels.add(labelMatcher.group(1).trim());
        }
        Matcher refMatcher = REF_PATTERN.matcher(activeTex);
        while (refMatcher.find()) {
            String label = refMatcher.group(1).trim();
            if (!label.isBlank() && !labels.contains(label)) {
                issues.add(new LatexLintIssue(LatexLintIssue.Severity.MINOR, "REF_WITHOUT_LABEL", "Reference target not defined: " + label, refMatcher.start(), refMatcher.end()));
            }
        }
        Matcher matcher = BIB_KEY_PATTERN.matcher(activeTex);
        while (matcher.find()) {
            for (String key : matcher.group(1).split(",")) {
                String trimmed = key.trim();
                if (!trimmed.isBlank() && !knownKeys.contains(trimmed)) {
                    issues.add(new LatexLintIssue(LatexLintIssue.Severity.MINOR, "CITE_NOT_IN_ORIGINAL_BIB", "Citation key not present in original bib: " + trimmed, matcher.start(), matcher.end()));
                }
            }
        }
        return issues;
    }

    private Map<String, Object> saveArtifact(PaperTask task, String type, String filename, String content, String contentType, Map<String, Object> metadata) {
        int version = artifacts.findFirstByTaskIdAndTypeOrderByVersionDesc(task.getId(), type)
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);
        String objectKey = storageService.storeArtifact(task.getUserId(), type, filename, content.getBytes(StandardCharsets.UTF_8), contentType);
        PaperTaskArtifact artifact = new PaperTaskArtifact(task.getId(), type, objectKey, version);
        Map<String, Object> meta = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        meta.put("filename", filename);
        meta.put("size", content.getBytes(StandardCharsets.UTF_8).length);
        artifact.setMetadataJson(toJson(meta));
        artifact = artifacts.save(artifact);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", artifact.getId());
        result.put("type", artifact.getType());
        result.put("objectKey", artifact.getObjectKey());
        result.put("version", artifact.getVersion());
        return result;
    }

    private String uniqueBibKey(LiteratureCard card, Set<String> usedKeys) {
        String firstAuthor = authors(card).split(" and ")[0].replaceAll("[^A-Za-z0-9]", "");
        if (firstAuthor.isBlank()) firstAuthor = "paper";
        String year = card.getPublicationYear() == null ? "nd" : String.valueOf(card.getPublicationYear());
        String base = (firstAuthor + year).toLowerCase();
        String key = base;
        int suffix = 1;
        while (usedKeys.contains(key)) {
            key = base + suffix++;
        }
        return key;
    }

    private String authors(LiteratureCard card) {
        if (!notBlank(card.getAuthors())) return "Unknown";
        try {
            List<String> authors = objectMapper.readValue(card.getAuthors(), new TypeReference<List<String>>() {});
            return authors.isEmpty() ? "Unknown" : String.join(" and ", authors);
        } catch (Exception ex) {
            return card.getAuthors();
        }
    }

    private String escapeBib(String value) {
        return value == null ? "" : value.replace("{", "\\{").replace("}", "\\}");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
