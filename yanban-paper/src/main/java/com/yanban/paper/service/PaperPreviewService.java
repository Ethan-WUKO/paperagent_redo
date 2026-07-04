package com.yanban.paper.service;

import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.LiteratureCardRepository;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.domain.SuggestionEvidence;
import com.yanban.paper.domain.SuggestionEvidenceRepository;
import com.yanban.paper.domain.SuggestionRepository;
import com.yanban.paper.web.PaperSuggestionResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaperPreviewService {

    private static final Set<String> ALLOWED_STATUSES = Set.of("PROPOSED", "ACCEPTED", "REJECTED", "NEEDS_USER_DATA");

    private final PaperTaskRepository tasks;
    private final SuggestionRepository suggestions;
    private final SuggestionEvidenceRepository evidenceRepository;
    private final LiteratureCardRepository literatureCards;
    private final PaperTaskArtifactRepository artifacts;

    public PaperPreviewService(PaperTaskRepository tasks,
                               SuggestionRepository suggestions,
                               SuggestionEvidenceRepository evidenceRepository,
                               LiteratureCardRepository literatureCards,
                               PaperTaskArtifactRepository artifacts) {
        this.tasks = tasks;
        this.suggestions = suggestions;
        this.evidenceRepository = evidenceRepository;
        this.literatureCards = literatureCards;
        this.artifacts = artifacts;
    }

    @Transactional(readOnly = true)
    public List<PaperSuggestionResponse> listSuggestions(Long userId, Long taskId) {
        assertOwned(userId, taskId);
        List<Suggestion> taskSuggestions = suggestions.findByTaskIdOrderByCreatedAt(taskId);
        Map<Long, List<LiteratureCard>> evidenceCards = evidenceCards(taskSuggestions);
        return taskSuggestions.stream()
                .map(suggestion -> PaperSuggestionResponse.from(suggestion, evidenceCards.getOrDefault(suggestion.getId(), List.of())))
                .toList();
    }

    @Transactional
    public PaperSuggestionResponse updateSuggestionStatus(Long userId, Long taskId, Long suggestionId, String status) {
        assertOwned(userId, taskId);
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的建议状态");
        }
        Suggestion suggestion = suggestions.findById(suggestionId)
                .filter(item -> item.getTaskId().equals(taskId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "建议不存在"));
        suggestion.setStatus(normalized);
        Suggestion saved = suggestions.save(suggestion);
        Map<Long, List<LiteratureCard>> evidenceCards = evidenceCards(List.of(saved));
        return PaperSuggestionResponse.from(saved, evidenceCards.getOrDefault(saved.getId(), List.of()));
    }

    @Transactional(readOnly = true)
    public List<PaperTaskArtifact> listArtifacts(Long userId, Long taskId) {
        assertOwned(userId, taskId);
        return artifacts.findByTaskIdOrderByCreatedAt(taskId);
    }

    private Map<Long, List<LiteratureCard>> evidenceCards(List<Suggestion> taskSuggestions) {
        if (taskSuggestions == null || taskSuggestions.isEmpty()) return Map.of();
        List<Long> suggestionIds = taskSuggestions.stream().map(Suggestion::getId).toList();
        List<SuggestionEvidence> evidences = evidenceRepository.findBySuggestionIdIn(suggestionIds);
        Set<Long> cardIds = evidences.stream()
                .map(SuggestionEvidence::getCardId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, LiteratureCard> cardById = literatureCards.findAllById(cardIds).stream()
                .collect(Collectors.toMap(LiteratureCard::getId, card -> card));
        Map<Long, List<LiteratureCard>> result = new LinkedHashMap<>();
        for (SuggestionEvidence evidence : evidences) {
            LiteratureCard card = cardById.get(evidence.getCardId());
            if (card != null) {
                result.computeIfAbsent(evidence.getSuggestionId(), ignored -> new ArrayList<>()).add(card);
            }
        }
        return result;
    }

    private void assertOwned(Long userId, Long taskId) {
        tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在"));
    }
}
