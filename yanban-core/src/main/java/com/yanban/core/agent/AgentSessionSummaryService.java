package com.yanban.core.agent;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSessionSummaryService {

    private final AgentSessionSummaryRepository summaries;

    public AgentSessionSummaryService(AgentSessionSummaryRepository summaries) {
        this.summaries = summaries;
    }

    @Transactional(readOnly = true)
    public Optional<AgentSessionSummary> findBySessionAndUser(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return Optional.empty();
        }
        return summaries.findBySessionIdAndUserId(sessionId, userId);
    }

    @Transactional
    public AgentSessionSummary upsert(AgentSessionSummaryUpdate update) {
        Optional<AgentSessionSummary> existing = summaries.findBySessionIdAndUserId(update.sessionId(), update.userId());
        AgentSessionSummary summary;
        if (existing.isPresent()) {
            summary = existing.get();
            summary.update(
                    update.summaryText(),
                    update.coveredMessageId(),
                    update.messageCount(),
                    update.modelProviderSnapshot(),
                    update.modelSnapshot()
            );
        } else {
            summary = new AgentSessionSummary(
                        update.sessionId(),
                        update.userId(),
                        update.summaryText(),
                        update.coveredMessageId(),
                        update.messageCount(),
                        update.modelProviderSnapshot(),
                        update.modelSnapshot()
            );
        }
        return summaries.saveAndFlush(summary);
    }

    @Transactional
    public void deleteBySession(Long sessionId) {
        if (sessionId != null) {
            summaries.deleteBySessionId(sessionId);
        }
    }
}
