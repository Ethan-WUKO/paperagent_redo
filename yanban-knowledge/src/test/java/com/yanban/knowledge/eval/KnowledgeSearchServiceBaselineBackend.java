package com.yanban.knowledge.eval;

import com.yanban.knowledge.service.KnowledgeSearchOptions;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import com.yanban.knowledge.service.KnowledgeSearchService;
import java.util.List;

public class KnowledgeSearchServiceBaselineBackend implements BaselineSearchBackend {

    private final KnowledgeSearchService searchService;

    public KnowledgeSearchServiceBaselineBackend(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public List<BaselineRagHit> search(RagSpikeEvalCase evalCase) {
        return searchService.search(evalCase.query(), new KnowledgeSearchOptions(
                        evalCase.userId(),
                        evalCase.topK(),
                        evalCase.projectId(),
                        false
                )).stream()
                .map(this::toHit)
                .toList();
    }

    private BaselineRagHit toHit(KnowledgeSearchResult result) {
        return new BaselineRagHit(
                result.documentId(),
                result.filename(),
                result.chunkIndex(),
                result.chunkText(),
                result.score(),
                result.citationId(),
                result.source(),
                result.versionStatus(),
                result.isPublic() ? "PUBLIC" : "PRIVATE"
        );
    }
}
