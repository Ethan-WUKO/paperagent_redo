package com.yanban.api.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.SandboxOutputAnalysisService;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/** Optional tool-free output interpretation; execution facts and application authority remain elsewhere. */
@Service
@ConditionalOnProperty(prefix = "yanban.sandbox", name = "enabled", havingValue = "true")
class CandidateValidationAnalysisProjectionService {
    private final CandidateSandboxValidationRepository validations;
    private final AgentSessionRepository sessions;
    private final SandboxOutputAnalysisService analysis;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    CandidateValidationAnalysisProjectionService(CandidateSandboxValidationRepository validations,
                                                 AgentSessionRepository sessions,
                                                 SandboxOutputAnalysisService analysis,
                                                 ObjectMapper json, JdbcTemplate jdbc,
                                                 PlatformTransactionManager transactions) {
        this.validations = validations; this.sessions = sessions; this.analysis = analysis;
        this.json = json; this.jdbc = jdbc; this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void analyzeAfterCommit(String validationId) {
        try {
            Input input = requiresNew.execute(status -> validations.lockByValidationId(validationId)
                    .filter(value -> value.receiptJson() != null && value.analysisSummary() == null)
                    .map(value -> new Input(value.userId(), value.sessionId(), read(value.receiptJson()))).orElse(null));
            if (input == null) return;
            String summary = analysis.analyze(input.userId(), sessions.findById(input.sessionId()).orElse(null),
                    input.receipt(), "candidate-validation-analysis-" + validationId);
            if (!StringUtils.hasText(summary)) return;
            requiresNew.executeWithoutResult(status -> validations.lockByValidationId(validationId).ifPresent(value -> {
                value.saveAnalysis(summary, SandboxOutputAnalysisService.DISCLAIMER, dbNow());
                validations.saveAndFlush(value);
            }));
        } catch (Exception ignored) {
            // Analysis is display-only. The immutable Broker receipt remains authoritative and visible.
        }
    }

    private SandboxReceipt read(String value) {
        try { return json.readValue(value, SandboxReceipt.class); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private LocalDateTime dbNow() { return jdbc.queryForObject("select current_timestamp", LocalDateTime.class); }
    private record Input(long userId, long sessionId, SandboxReceipt receipt) { }
}
