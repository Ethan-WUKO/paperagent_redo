package com.yanban.paper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexLintIssue;
import com.yanban.paper.latex.LatexMaskingService;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.LatexSectionRole;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Bounded critic-orator loop for unresolved Introduction citation suggestions. */
@Service
public class PaperCitationClosureService {

    private static final Logger log = LoggerFactory.getLogger(PaperCitationClosureService.class);
    static final int BATCH_SIZE = 4;
    static final int MAX_ROUNDS = 3;
    private static final Set<String> CRITIC_ACTIONS = Set.of("CURRENT_SUPPORTED", "RELOCATE", "NARROW", "SPLIT", "NO_FIT");
    private static final Set<String> ORATOR_DECISIONS = Set.of("APPLY", "DISAGREE", "NO_SAFE_PATCH");
    private static final Set<String> ORATOR_OPERATIONS = Set.of("KEEP", "RELOCATE", "NARROW", "SPLIT");
    private static final Set<String> VERDICTS = Set.of("SUPPORTED", "PARTIAL", "REJECTED");
    private static final Set<String> ELIGIBLE_PRIOR_VERDICTS = Set.of("PARTIAL", "REJECTED", "UNREVIEWED");
    private static final Pattern SECTION_COMMAND = Pattern.compile("\\\\(?:section|subsection|subsubsection)\\*?\\s*\\{");
    private static final Pattern CITATION_SLOT = Pattern.compile("\\\\yanbancitationslot\\s*\\{\\d+}");

    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidenceRepository;
    private final LiteratureCardRepository cards;
    private final PaperPromptService promptService;
    private final PaperModelClient modelClient;
    private final LatexMaskingService maskingService;
    private final ObjectMapper objectMapper;
    private final PaperCitationClosurePersistenceService persistenceService;

    public PaperCitationClosureService(SuggestionRepository suggestions,
                                       SuggestionEvidenceRepository evidenceRepository,
                                       LiteratureCardRepository cards,
                                       PaperPromptService promptService,
                                       PaperModelClient modelClient,
                                       LatexMaskingService maskingService,
                                       ObjectMapper objectMapper,
                                       PaperCitationClosurePersistenceService persistenceService) {
        this.suggestions = suggestions;
        this.evidenceRepository = evidenceRepository;
        this.cards = cards;
        this.promptService = promptService;
        this.modelClient = modelClient;
        this.maskingService = maskingService;
        this.objectMapper = objectMapper;
        this.persistenceService = persistenceService;
    }

    public ClosureResult close(Long taskId, LatexDocument finalDraft) {
        IntroTarget intro = introduction(finalDraft);
        if (intro == null || intro.text().isBlank()) {
            ClosureResult result = ClosureResult.notRun("No Introduction section was available for citation closure.");
            persistenceService.persist(taskId, result);
            return result;
        }

        List<Suggestion> taskSuggestions = suggestions.findByTaskIdOrderByCreatedAt(taskId);
        List<Long> suggestionIds = taskSuggestions.stream().map(Suggestion::getId).toList();
        Map<Long, List<Long>> evidenceBySuggestion = evidenceRepository.findBySuggestionIdIn(suggestionIds).stream()
                .collect(Collectors.groupingBy(
                        SuggestionEvidence::getSuggestionId,
                        LinkedHashMap::new,
                        Collectors.mapping(SuggestionEvidence::getCardId, Collectors.toList())));
        Set<Long> cardIds = evidenceBySuggestion.values().stream().flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, LiteratureCard> cardById = cards.findAllById(cardIds).stream()
                .collect(Collectors.toMap(LiteratureCard::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<ClosureCandidate> candidates = new ArrayList<>();
        for (Suggestion suggestion : taskSuggestions) {
            Map<String, Object> patch = readMap(suggestion.getPatchJson());
            List<Long> evidenceIds = evidenceBySuggestion.getOrDefault(suggestion.getId(), List.of()).stream()
                    .filter(cardById::containsKey)
                    .distinct()
                    .toList();
            if (eligible(suggestion, patch, evidenceIds)) {
                candidates.add(new ClosureCandidate(suggestion, patch, evidenceIds));
            }
        }

        List<ClosureOutcome> outcomes = new ArrayList<>();
        List<TextRange> acceptedRanges = new ArrayList<>();
        int batchCount = 0;
        for (int start = 0; start < candidates.size(); start += BATCH_SIZE) {
            batchCount++;
            outcomes.addAll(processBatch(
                    taskId,
                    batchCount,
                    candidates.subList(start, Math.min(start + BATCH_SIZE, candidates.size())),
                    intro,
                    cardById,
                    acceptedRanges));
        }
        Map<Long, Integer> candidateOrder = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            candidateOrder.put(candidates.get(index).id(), index);
        }
        outcomes.sort((left, right) -> Integer.compare(
                candidateOrder.getOrDefault(left.suggestionId(), Integer.MAX_VALUE),
                candidateOrder.getOrDefault(right.suggestionId(), Integer.MAX_VALUE)));
        ClosureResult result = new ClosureResult(
                "COMPLETED",
                candidates.size(),
                batchCount,
                outcomes.stream().filter(ClosureOutcome::accepted).count(),
                outcomes.stream().filter(item -> !item.accepted()).count(),
                List.copyOf(outcomes),
                candidates.isEmpty() ? "No unresolved grounded citation suggestions required closure."
                        : "Citation closure completed with per-suggestion isolation.");
        log.info("PaperCitationClosure taskId={} status={} eligible={} batches={} accepted={} reportOnly={}",
                taskId, result.status(), result.eligibleCount(), result.batchCount(),
                result.acceptedCount(), result.reportOnlyCount());
        persistenceService.persist(taskId, result);
        return result;
    }

    private List<ClosureOutcome> processBatch(Long taskId,
                                              int batchIndex,
                                              List<ClosureCandidate> batch,
                                              IntroTarget intro,
                                              Map<Long, LiteratureCard> cardById,
                                              List<TextRange> acceptedRanges) {
        Map<Long, WorkState> unresolved = new LinkedHashMap<>();
        for (ClosureCandidate candidate : batch) unresolved.put(candidate.id(), new WorkState(candidate));
        List<ClosureOutcome> outcomes = new ArrayList<>();

        for (int round = 1; round <= MAX_ROUNDS && !unresolved.isEmpty(); round++) {
            List<WorkState> active = List.copyOf(unresolved.values());
            for (WorkState state : active) {
                state.attempts++;
                state.trace(round).put("round", round);
            }

            log.info("PaperCitationClosure taskId={} batch={} round={} phase=critic suggestions={}",
                    taskId, batchIndex, round, active.stream().map(WorkState::id).toList());
            ModelBatch<Diagnosis> diagnoses = diagnose(active, intro.text(), cardById, round);
            List<Actionable> actionable = new ArrayList<>();
            for (WorkState state : active) {
                Diagnosis diagnosis = diagnoses.values().get(state.id());
                Map<String, Object> trace = state.trace(round);
                if (diagnosis == null) {
                    state.feedback = blankToDefault(diagnoses.error(), "The closure critic omitted this suggestion.");
                    trace.put("criticStatus", "UNAVAILABLE");
                    trace.put("reason", truncate(state.feedback, 500));
                    continue;
                }
                trace.put("criticAction", diagnosis.action());
                trace.put("criticReason", truncate(diagnosis.reason(), 500));
                if ("NO_FIT".equals(diagnosis.action()) || diagnosis.evidenceCardIds().isEmpty()) {
                    String reason = blankToDefault(diagnosis.reason(), "No safe Introduction claim is supported by the supplied evidence.");
                    outcomes.add(reportOnly(state, reason, intro));
                    unresolved.remove(state.id());
                    continue;
                }
                actionable.add(new Actionable(state, diagnosis));
            }

            ModelBatch<OratorPatch> oratorPatches = actionable.isEmpty()
                    ? ModelBatch.empty()
                    : loggedOrate(taskId, batchIndex, round, actionable, intro.text(), cardById);
            List<ValidatedPatch> validPatches = new ArrayList<>();
            for (Actionable item : actionable) {
                WorkState state = item.state();
                OratorPatch patch = oratorPatches.values().get(state.id());
                Map<String, Object> trace = state.trace(round);
                if (patch == null) {
                    state.feedback = blankToDefault(oratorPatches.error(), "The orator omitted this suggestion.");
                    trace.put("oratorStatus", "UNAVAILABLE");
                    trace.put("reason", truncate(state.feedback, 500));
                    continue;
                }
                trace.put("oratorDecision", patch.decision());
                trace.put("operation", patch.operation());
                if (!"APPLY".equals(patch.decision())) {
                    state.feedback = blankToDefault(patch.reason(), "The orator found no safe local patch.");
                    trace.put("reason", truncate(state.feedback, 500));
                    continue;
                }
                PatchValidation validation = validatePatch(patch, intro.text(), acceptedRanges);
                if (!validation.valid()) {
                    state.feedback = validation.reason();
                    trace.put("validation", "REJECTED");
                    trace.put("reason", truncate(validation.reason(), 500));
                    continue;
                }
                trace.put("validation", "PASSED");
                validPatches.add(new ValidatedPatch(state, item.diagnosis(), patch, validation.range()));
            }

            ModelBatch<Verification> verifications = validPatches.isEmpty()
                    ? ModelBatch.empty()
                    : loggedVerify(taskId, batchIndex, round, validPatches, cardById);
            for (ValidatedPatch item : validPatches) {
                WorkState state = item.state();
                Verification verification = verifications.values().get(state.id());
                Map<String, Object> trace = state.trace(round);
                if (verification == null) {
                    state.feedback = blankToDefault(verifications.error(), "The final critic omitted this suggestion.");
                    trace.put("verification", "UNAVAILABLE");
                    trace.put("reason", truncate(state.feedback, 500));
                    continue;
                }
                trace.put("verification", verification.verdict());
                trace.put("verificationReason", truncate(verification.reason(), 500));
                if (!verification.supported()) {
                    String partialGuidance = "PARTIAL".equals(verification.verdict())
                            && !verification.supportedAnchor().isBlank()
                            ? " The critic identified this supported subclause for the next repair: "
                            + verification.supportedAnchor()
                            : "";
                    state.feedback = blankToDefault(
                            verification.reason(), "The proposed claim was not fully supported.")
                            + partialGuidance;
                    continue;
                }
                acceptedRanges.add(item.range());
                outcomes.add(accepted(state, item, verification, item.patch().citationAnchor(), intro));
                unresolved.remove(state.id());
            }
        }

        for (WorkState state : unresolved.values()) {
            outcomes.add(reportOnly(state,
                    blankToDefault(state.feedback, "No safe supported patch was approved after three rounds."), intro));
        }
        return outcomes;
    }

    private ModelBatch<OratorPatch> loggedOrate(Long taskId,
                                                int batchIndex,
                                                int round,
                                                List<Actionable> actionable,
                                                String introduction,
                                                Map<Long, LiteratureCard> cardById) {
        log.info("PaperCitationClosure taskId={} batch={} round={} phase=orator suggestions={}",
                taskId, batchIndex, round,
                actionable.stream().map(item -> item.state().id()).toList());
        return orate(actionable, introduction, cardById, round);
    }

    private ModelBatch<Verification> loggedVerify(Long taskId,
                                                  int batchIndex,
                                                  int round,
                                                  List<ValidatedPatch> patches,
                                                  Map<Long, LiteratureCard> cardById) {
        log.info("PaperCitationClosure taskId={} batch={} round={} phase=verifier suggestions={}",
                taskId, batchIndex, round,
                patches.stream().map(item -> item.state().id()).toList());
        return verify(patches, cardById, round);
    }

    private ModelBatch<Diagnosis> diagnose(List<WorkState> states,
                                           String introduction,
                                           Map<Long, LiteratureCard> cardById,
                                           int round) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (WorkState state : states) {
            ClosureCandidate candidate = state.candidate;
            Map<String, Object> item = baseCandidatePayload(candidate, cardById);
            item.put("previousFeedback", state.feedback);
            payload.add(item);
        }
        Map<String, Object> envelope = Map.of(
                "expectedSuggestionIds", states.stream().map(WorkState::id).toList(),
                "candidates", payload);
        try {
            String prompt = promptService.render("citation-closure-critic", Map.of(
                    "round", round,
                    "introduction", introduction,
                    "candidates", toJson(envelope)));
            String response = modelClient.complete(
                    "You are an independent academic evidence critic. Return strict JSON only and do not force-fit citations.",
                    prompt, 0.0, 4096);
            Map<Long, Map<String, Object>> raw = indexedItems(response, "diagnoses", states.stream().map(WorkState::id).collect(Collectors.toSet()));
            Map<Long, Diagnosis> result = new LinkedHashMap<>();
            for (WorkState state : states) {
                Map<String, Object> value = raw.get(state.id());
                if (value == null) continue;
                String action = uppercase(value.get("action"));
                if (!CRITIC_ACTIONS.contains(action)) action = "NO_FIT";
                List<Long> accepted = allowedIds(value.get("supportedEvidenceCardIds"), state.candidate.evidenceIds());
                if (!"NO_FIT".equals(action) && accepted.isEmpty()) action = "NO_FIT";
                result.put(state.id(), new Diagnosis(
                        action,
                        accepted,
                        string(value.get("supportedFact")),
                        strings(value.get("unsupportedQualifiers")),
                        string(value.get("placementGuidance")),
                        string(value.get("reason"))));
            }
            return new ModelBatch<>(Map.copyOf(result), "");
        } catch (Exception ex) {
            return new ModelBatch<>(Map.of(), "Closure critic failed: " + rootMessage(ex));
        }
    }

    private ModelBatch<OratorPatch> orate(List<Actionable> actionable,
                                          String introduction,
                                          Map<Long, LiteratureCard> cardById,
                                          int round) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Actionable item : actionable) {
            Map<String, Object> candidate = baseCandidatePayload(item.state().candidate, cardById);
            candidate.put("criticDiagnosis", item.diagnosis().asMap());
            candidate.put("previousFeedback", item.state().feedback);
            payload.add(candidate);
        }
        Map<String, Object> envelope = Map.of(
                "expectedSuggestionIds", actionable.stream().map(item -> item.state().id()).toList(),
                "candidates", payload);
        try {
            String prompt = promptService.render("citation-closure-orator", Map.of(
                    "round", round,
                    "introduction", introduction,
                    "candidates", toJson(envelope)));
            String response = modelClient.complete(
                    "You are a conservative academic rhetorical editor. Return strict JSON only and make at most one local patch per suggestion.",
                    prompt, 0.1, 4096);
            Set<Long> expected = actionable.stream().map(item -> item.state().id()).collect(Collectors.toSet());
            Map<Long, Map<String, Object>> raw = indexedItems(response, "patches", expected);
            Map<Long, OratorPatch> result = new LinkedHashMap<>();
            for (Actionable item : actionable) {
                Long id = item.state().id();
                Map<String, Object> value = raw.get(id);
                if (value == null) continue;
                String decision = uppercase(value.get("decision"));
                if (!ORATOR_DECISIONS.contains(decision)) decision = "NO_SAFE_PATCH";
                String operation = uppercase(value.get("operation"));
                if (!ORATOR_OPERATIONS.contains(operation)) operation = "KEEP";
                result.put(id, new OratorPatch(
                        decision,
                        operation,
                        string(value.get("originalAnchor")),
                        string(value.get("replacementText")),
                        string(value.get("citationAnchor")),
                        string(value.get("reason"))));
            }
            return new ModelBatch<>(Map.copyOf(result), "");
        } catch (Exception ex) {
            return new ModelBatch<>(Map.of(), "Citation orator failed: " + rootMessage(ex));
        }
    }

    private ModelBatch<Verification> verify(List<ValidatedPatch> patches,
                                            Map<Long, LiteratureCard> cardById,
                                            int round) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ValidatedPatch item : patches) {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("suggestionId", item.state().id());
            candidate.put("replacementText", item.patch().replacementText());
            candidate.put("citationAnchor", item.patch().citationAnchor());
            candidate.put("criticSupportedFact", item.diagnosis().supportedFact());
            candidate.put("evidence", evidencePayload(item.diagnosis().evidenceCardIds(), cardById));
            payload.add(candidate);
        }
        Map<String, Object> envelope = Map.of(
                "expectedSuggestionIds", patches.stream().map(item -> item.state().id()).toList(),
                "candidates", payload);
        try {
            String prompt = promptService.render("citation-closure-verifier", Map.of(
                    "round", round,
                    "candidates", toJson(envelope)));
            String response = modelClient.complete(
                    "You are the final independent citation critic. Return strict JSON only and reject unsupported qualifiers.",
                    prompt, 0.0, 3072);
            Set<Long> expected = patches.stream().map(item -> item.state().id()).collect(Collectors.toSet());
            Map<Long, Map<String, Object>> raw = indexedItems(response, "verifications", expected);
            Map<Long, Verification> result = new LinkedHashMap<>();
            for (ValidatedPatch item : patches) {
                Long id = item.state().id();
                Map<String, Object> value = raw.get(id);
                if (value == null) continue;
                String verdict = uppercase(value.get("verdict"));
                if (!VERDICTS.contains(verdict)) verdict = "REJECTED";
                List<Long> accepted = allowedIds(value.get("acceptedEvidenceCardIds"), item.diagnosis().evidenceCardIds());
                String supportedAnchor = "PARTIAL".equals(verdict)
                        ? safeClause(item.patch().citationAnchor(), string(value.get("supportedAnchor"))) : "";
                if (("SUPPORTED".equals(verdict) || "PARTIAL".equals(verdict)) && accepted.isEmpty()) verdict = "REJECTED";
                if ("PARTIAL".equals(verdict) && supportedAnchor.isBlank()) accepted = List.of();
                if ("REJECTED".equals(verdict)) accepted = List.of();
                result.put(id, new Verification(
                        verdict,
                        accepted,
                        supportedAnchor,
                        blankToDefault(string(value.get("reason")), "No final evidence justification was supplied.")));
            }
            return new ModelBatch<>(Map.copyOf(result), "");
        } catch (Exception ex) {
            return new ModelBatch<>(Map.of(), "Final citation verification failed: " + rootMessage(ex));
        }
    }

    private PatchValidation validatePatch(OratorPatch patch, String introduction, List<TextRange> acceptedRanges) {
        if (patch.originalAnchor().length() < 20 || patch.replacementText().length() < 20 || patch.citationAnchor().length() < 20) {
            return PatchValidation.invalid("The proposed anchors or replacement are too short for safe application.");
        }
        List<Integer> occurrences = occurrences(introduction, patch.originalAnchor());
        if (occurrences.size() != 1) {
            return PatchValidation.invalid("The proposed original anchor is not unique in the current Introduction.");
        }
        int start = occurrences.get(0);
        TextRange range = new TextRange(start, start + patch.originalAnchor().length());
        if (acceptedRanges.stream().anyMatch(range::overlaps)) {
            return PatchValidation.invalid("The proposed patch overlaps another accepted citation repair.");
        }
        int maxLength = Math.max(2400, patch.originalAnchor().length() * 2 + 400);
        if (patch.replacementText().length() > maxLength) {
            return PatchValidation.invalid("The proposed local replacement is too large.");
        }
        if (SECTION_COMMAND.matcher(patch.replacementText()).find() || CITATION_SLOT.matcher(patch.replacementText()).find()) {
            return PatchValidation.invalid("The orator may not add section commands or citation-slot markers.");
        }
        if (!protectedValues(patch.originalAnchor()).equals(protectedValues(patch.replacementText()))) {
            return PatchValidation.invalid("The proposed replacement changed protected LaTeX commands, citations, references, or math.");
        }
        boolean blocker = maskingService.lint(patch.replacementText()).stream()
                .anyMatch(issue -> issue.severity() == LatexLintIssue.Severity.BLOCKER);
        if (blocker) return PatchValidation.invalid("The proposed replacement failed LaTeX lint validation.");
        if (!safeClause(patch.replacementText(), patch.citationAnchor()).equals(patch.citationAnchor())) {
            return PatchValidation.invalid("The citation anchor is not one unique complete clause inside the replacement.");
        }
        Map<String, Object> persistedPatch = new LinkedHashMap<>();
        persistedPatch.put("originalAnchor", patch.originalAnchor());
        persistedPatch.put("replacementText", patch.replacementText());
        persistedPatch.put("citationAnchor", patch.citationAnchor());
        if (toJson(persistedPatch).length() > 7_000) {
            return PatchValidation.invalid("The proposed local patch is too large for safe persisted application.");
        }
        String candidateIntro = introduction.substring(0, range.start()) + patch.replacementText() + introduction.substring(range.end());
        if (occurrences(candidateIntro, patch.citationAnchor()).size() != 1) {
            return PatchValidation.invalid("The citation anchor would not be unique after applying the proposed replacement.");
        }
        return PatchValidation.valid(range);
    }

    private List<String> protectedValues(String text) {
        return new ArrayList<>(maskingService.mask(text).placeholders().values());
    }

    private ClosureOutcome accepted(WorkState state,
                                    ValidatedPatch patch,
                                    Verification verification,
                                    String finalAnchor,
                                    IntroTarget intro) {
        return new ClosureOutcome(
                state.id(), true, state.attempts, patch.patch().operation(),
                patch.patch().originalAnchor(), patch.patch().replacementText(), finalAnchor,
                verification.evidenceCardIds(), verification.asMap(), verification.reason(),
                intro.orderIndex(), intro.title(), state.rounds());
    }

    private ClosureOutcome reportOnly(WorkState state, String reason, IntroTarget intro) {
        return new ClosureOutcome(
                state.id(), false, state.attempts, "NONE", "", "", "", List.of(), Map.of(),
                reason, intro.orderIndex(), intro.title(), state.rounds());
    }

    private Map<String, Object> baseCandidatePayload(ClosureCandidate candidate,
                                                     Map<Long, LiteratureCard> cardById) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("suggestionId", candidate.id());
        item.put("statement", candidate.suggestion().getStatement());
        item.put("category", candidate.suggestion().getCategory());
        item.put("currentAnchor", value(candidate.patch(), "anchor"));
        item.put("priorCitationCritic", candidate.patch().getOrDefault("citationCritic", Map.of()));
        item.put("priorApplicationDecision", candidate.patch().getOrDefault("applicationDecision", Map.of()));
        item.put("evidence", evidencePayload(candidate.evidenceIds(), cardById));
        return item;
    }

    private List<Map<String, Object>> evidencePayload(List<Long> evidenceIds,
                                                      Map<Long, LiteratureCard> cardById) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long cardId : evidenceIds) {
            LiteratureCard card = cardById.get(cardId);
            if (card == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cardId", cardId);
            item.put("title", card.getTitle());
            item.put("venue", card.getVenue());
            item.put("year", card.getPublicationYear());
            item.put("abstract", truncate(card.getAbstractText(), 1200));
            item.put("evidenceAnalysis", truncate(card.getAnalysisJson(), 700));
            result.add(item);
        }
        return result;
    }

    private boolean eligible(Suggestion suggestion, Map<String, Object> patch, List<Long> evidenceIds) {
        if (suggestion == null || suggestion.getId() == null || evidenceIds.isEmpty()) return false;
        if (!"ADVOCACY".equalsIgnoreCase(suggestion.getTrack())) return false;
        if ("ACCEPTED".equalsIgnoreCase(suggestion.getStatus()) && Boolean.TRUE.equals(suggestion.getApplicable())) return false;
        Map<String, Object> critic = mapValue(patch.get("citationCritic"));
        String verdict = uppercase(critic.get("verdict"));
        if (ELIGIBLE_PRIOR_VERDICTS.contains(verdict)) return true;
        Map<String, Object> application = mapValue(patch.get("applicationDecision"));
        String status = uppercase(application.get("status"));
        return status.startsWith("NO_") || string(value(patch, "anchor")).isBlank();
    }

    private IntroTarget introduction(LatexDocument document) {
        if (document == null || document.sections() == null) return null;
        return document.sections().stream()
                .filter(section -> section.role() == LatexSectionRole.INTRO
                        || (section.title() != null && section.title().toLowerCase(Locale.ROOT).contains("intro")))
                .findFirst()
                .map(section -> new IntroTarget(section.orderIndex(), section.title(), section.rawText()))
                .orElse(null);
    }

    private Map<Long, Map<String, Object>> indexedItems(String response, String key, Set<Long> expected) {
        Map<String, Object> root = readMap(response);
        Object values = root.get(key);
        if (!(values instanceof List<?> list)) return Map.of();
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        Set<Long> duplicates = new LinkedHashSet<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            raw.forEach((itemKey, itemValue) -> item.put(String.valueOf(itemKey), itemValue));
            Long suggestionId = longValue(item.get("suggestionId"));
            if (suggestionId == null || !expected.contains(suggestionId)) continue;
            if (result.containsKey(suggestionId)) duplicates.add(suggestionId);
            else result.put(suggestionId, item);
        }
        duplicates.forEach(result::remove);
        return Map.copyOf(result);
    }

    private List<Long> allowedIds(Object raw, List<Long> allowed) {
        if (!(raw instanceof List<?> values)) return List.of();
        Set<Long> allowedSet = new LinkedHashSet<>(allowed);
        Set<Long> result = new LinkedHashSet<>();
        for (Object value : values) {
            Long id = longValue(value);
            if (id != null && allowedSet.contains(id)) result.add(id);
        }
        return List.copyOf(result);
    }

    private String safeClause(String source, String candidate) {
        if (source == null || candidate == null || candidate.trim().length() < 20) return "";
        String anchor = candidate.trim();
        int first = source.indexOf(anchor);
        if (first < 0 || source.indexOf(anchor, first + anchor.length()) >= 0) return "";
        int before = previousNonWhitespace(source, first - 1);
        int after = nextNonWhitespace(source, first + anchor.length());
        if ((before >= 0 && !isClauseBoundary(source.charAt(before)))
                || (after < source.length() && !isClauseBoundary(source.charAt(after)))) return "";
        return anchor;
    }

    private int previousNonWhitespace(String value, int index) {
        while (index >= 0 && Character.isWhitespace(value.charAt(index))) index--;
        return index;
    }

    private int nextNonWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private boolean isClauseBoundary(char value) {
        return ",;:.!?()[]".indexOf(value) >= 0;
    }

    private List<Integer> occurrences(String text, String needle) {
        List<Integer> result = new ArrayList<>();
        if (text == null || needle == null || needle.isBlank()) return result;
        int offset = 0;
        while (offset <= text.length() - needle.length()) {
            int found = text.indexOf(needle, offset);
            if (found < 0) break;
            result.add(found);
            offset = found + Math.max(1, needle.length());
        }
        return result;
    }

    private Map<String, Object> readMap(String text) {
        if (text == null || text.isBlank()) return Map.of();
        try {
            String source = text.trim();
            int start = source.indexOf('{');
            int end = source.lastIndexOf('}');
            if (start >= 0 && end > start) source = source.substring(start, end + 1);
            return objectMapper.readValue(source, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(this::string).filter(item -> !item.isBlank()).toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize citation closure payload", ex);
        }
    }

    private Object value(Map<String, Object> values, String key) {
        return values == null ? "" : values.getOrDefault(key, "");
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            String normalized = string(value).replace("card-", "");
            return normalized.isBlank() ? null : Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String uppercase(Object value) {
        return string(value).toUpperCase(Locale.ROOT);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + " ...";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }

    private record IntroTarget(int orderIndex, String title, String text) {}

    private record ClosureCandidate(Suggestion suggestion, Map<String, Object> patch, List<Long> evidenceIds) {
        private Long id() { return suggestion.getId(); }
    }

    private static final class WorkState {
        private final ClosureCandidate candidate;
        private final Map<Integer, Map<String, Object>> traces = new LinkedHashMap<>();
        private int attempts;
        private String feedback = "";

        private WorkState(ClosureCandidate candidate) { this.candidate = candidate; }
        private Long id() { return candidate.id(); }
        private Map<String, Object> trace(int round) {
            return traces.computeIfAbsent(round, ignored -> new LinkedHashMap<>());
        }
        private List<Map<String, Object>> rounds() { return List.copyOf(traces.values()); }
    }

    private record Diagnosis(String action,
                             List<Long> evidenceCardIds,
                             String supportedFact,
                             List<String> unsupportedQualifiers,
                             String placementGuidance,
                             String reason) {
        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", action);
            result.put("supportedEvidenceCardIds", evidenceCardIds);
            result.put("supportedFact", supportedFact);
            result.put("unsupportedQualifiers", unsupportedQualifiers);
            result.put("placementGuidance", placementGuidance);
            result.put("reason", reason);
            return result;
        }
    }

    private record Actionable(WorkState state, Diagnosis diagnosis) {}

    private record OratorPatch(String decision,
                               String operation,
                               String originalAnchor,
                               String replacementText,
                               String citationAnchor,
                               String reason) {}

    private record ValidatedPatch(WorkState state, Diagnosis diagnosis, OratorPatch patch, TextRange range) {}

    private record Verification(String verdict,
                                List<Long> evidenceCardIds,
                                String supportedAnchor,
                                String reason) {
        private boolean supported() {
            return !evidenceCardIds.isEmpty() && "SUPPORTED".equals(verdict);
        }

        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("verdict", verdict);
            result.put("acceptedEvidenceCardIds", evidenceCardIds);
            result.put("supportedAnchor", supportedAnchor);
            result.put("reason", reason);
            return result;
        }
    }

    private record ModelBatch<T>(Map<Long, T> values, String error) {
        private static <T> ModelBatch<T> empty() { return new ModelBatch<>(Map.of(), ""); }
    }

    private record PatchValidation(boolean valid, String reason, TextRange range) {
        private static PatchValidation valid(TextRange range) { return new PatchValidation(true, "", range); }
        private static PatchValidation invalid(String reason) { return new PatchValidation(false, reason, null); }
    }

    private record TextRange(int start, int end) {
        private boolean overlaps(TextRange other) { return start < other.end && other.start < end; }
    }

    public record ClosureOutcome(Long suggestionId,
                                 boolean accepted,
                                 int attempts,
                                 String operation,
                                 String originalAnchor,
                                 String replacementText,
                                 String citationAnchor,
                                 List<Long> acceptedEvidenceCardIds,
                                 Map<String, Object> finalCritic,
                                 String reason,
                                 int sectionOrder,
                                 String sectionTitle,
                                 List<Map<String, Object>> rounds) {
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("suggestionId", suggestionId);
            result.put("status", accepted ? "SUPPORTED" : "REPORT_ONLY");
            result.put("attempts", attempts);
            result.put("operation", operation);
            result.put("reason", reason);
            result.put("rounds", rounds);
            return result;
        }
    }

    public record ClosureResult(String status,
                                int eligibleCount,
                                int batchCount,
                                long acceptedCount,
                                long reportOnlyCount,
                                List<ClosureOutcome> outcomes,
                                String message) {
        private static ClosureResult notRun(String message) {
            return new ClosureResult("NOT_RUN", 0, 0, 0, 0, List.of(), message);
        }

        public Map<String, Object> summary() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("generatedBy", "paper-citation-closure-v1");
            result.put("status", status);
            result.put("maxRounds", MAX_ROUNDS);
            result.put("batchSize", BATCH_SIZE);
            result.put("eligibleCount", eligibleCount);
            result.put("batchCount", batchCount);
            result.put("acceptedCount", acceptedCount);
            result.put("reportOnlyCount", reportOnlyCount);
            result.put("message", message);
            result.put("outcomes", outcomes.stream().map(ClosureOutcome::asMap).toList());
            return result;
        }
    }
}
