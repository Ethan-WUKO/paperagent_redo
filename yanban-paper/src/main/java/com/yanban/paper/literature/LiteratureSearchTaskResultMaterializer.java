package com.yanban.paper.literature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiteratureSearchTaskResultMaterializer {

    private final LiteratureCardCatalogService cardCatalogService;
    private final LiteratureSearchTaskService taskService;
    private final ObjectMapper objectMapper;

    public LiteratureSearchTaskResultMaterializer(LiteratureCardCatalogService cardCatalogService,
                                                  LiteratureSearchTaskService taskService,
                                                  ObjectMapper objectMapper) {
        this.cardCatalogService = cardCatalogService;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void materializeAndSave(Long userId,
                                   Long taskId,
                                   AdHocLiteratureSearchService.AdHocLiteratureSearchResult result) throws JsonProcessingException {
        ensureNotCancelled(userId, taskId);
        List<AdHocLiteratureSearchService.AdHocLiteratureItem> items = new ArrayList<>();
        for (AdHocLiteratureSearchService.AdHocLiteratureItem item : result.items()) {
            ensureNotCancelled(userId, taskId);
            LiteratureCard card = cardCatalogService.upsertCard(toCandidate(item));
            items.add(item.withCardId(card.getId()));
        }
        AdHocLiteratureSearchService.AdHocLiteratureSearchResult enriched = new AdHocLiteratureSearchService.AdHocLiteratureSearchResult(
                result.query(),
                List.copyOf(items),
                result.rawCandidateCount(),
                result.uniqueCandidateCount(),
                result.sourceAttempts(),
                result.sourceFailures()
        );
        taskService.saveResult(
                userId,
                taskId,
                objectMapper.writeValueAsString(enriched),
                result.rawCandidateCount(),
                result.uniqueCandidateCount(),
                result.sourceAttempts(),
                objectMapper.writeValueAsString(result.sourceFailures())
        );
    }

    private void ensureNotCancelled(Long userId, Long taskId) {
        if (taskService.isCancellationRequested(userId, taskId)) {
            throw new LiteratureSearchTaskCancelledException();
        }
    }

    private LiteratureCandidate toCandidate(AdHocLiteratureSearchService.AdHocLiteratureItem item) {
        return new LiteratureCandidate(
                item.source(),
                item.doi(),
                item.arxivId(),
                item.openAlexId(),
                null,
                item.title(),
                item.authors(),
                item.year(),
                item.venue(),
                item.abstractText(),
                item.url(),
                null,
                null,
                List.of(),
                List.of(),
                item.sourceQuery()
        );
    }

    public static class LiteratureSearchTaskCancelledException extends RuntimeException {
    }
}
