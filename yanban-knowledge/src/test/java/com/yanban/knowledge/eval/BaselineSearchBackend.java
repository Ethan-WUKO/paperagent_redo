package com.yanban.knowledge.eval;

import java.util.List;

@FunctionalInterface
public interface BaselineSearchBackend {
    List<BaselineRagHit> search(RagSpikeEvalCase evalCase);
}
