package com.yanban.core.agent;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentTaskRegistry {

    public static final String SOURCE_PAPER_TASK = "PAPER_TASK";
    public static final String SOURCE_LITERATURE_SEARCH_TASK = "LITERATURE_SEARCH_TASK";

    private static final Logger log = LoggerFactory.getLogger(AgentTaskRegistry.class);

    private final AgentTaskRepository tasks;

    public AgentTaskRegistry(AgentTaskRepository tasks) {
        this.tasks = tasks;
    }

    public void upsertSafely(AgentTaskUpsertRequest request) {
        if (!isValid(request)) {
            return;
        }
        try {
            upsert(request);
        } catch (Exception ex) {
            log.warn("Failed to sync unified agent task taskType={} source={} sourceId={}",
                    request.taskType(),
                    request.source(),
                    request.sourceId(),
                    ex);
        }
    }

    @Transactional
    public AgentTask upsert(AgentTaskUpsertRequest request) {
        if (!isValid(request)) {
            throw new IllegalArgumentException("agent task upsert request is incomplete");
        }
        AgentTask task = tasks.findByTaskTypeAndSourceAndSourceId(
                        request.taskType(),
                        request.source(),
                        request.sourceId())
                .orElseGet(() -> new AgentTask(
                        request.userId(),
                        request.taskType(),
                        request.source(),
                        request.sourceId(),
                        request.status()
                ));
        apply(task, request);
        return tasks.save(task);
    }

    @Transactional(readOnly = true)
    public Optional<AgentTask> findBySource(String taskType, String source, Long sourceId) {
        return tasks.findByTaskTypeAndSourceAndSourceId(taskType, source, sourceId);
    }

    private void apply(AgentTask task, AgentTaskUpsertRequest request) {
        task.setUserId(request.userId());
        task.setProjectId(request.projectId());
        task.setTaskType(request.taskType());
        task.setSource(request.source());
        task.setSourceId(request.sourceId());
        task.setStatus(request.status());
        task.setStrategy(trimToNull(request.strategy()));
        task.setClientRequestId(trimToNull(request.clientRequestId()));
        task.setTitle(trimToNull(request.title()));
        task.setInputSummary(trimToNull(request.inputSummary()));
        task.setProgressPercent(request.progressPercent());
        task.setCurrentStage(trimToNull(request.currentStage()));
        task.setErrorCode(trimToNull(request.errorCode()));
        task.setErrorMessage(trimToNull(request.errorMessage()));
        task.setCancellationReason(trimToNull(request.cancellationReason()));
        task.setRetryCount(request.retryCount());
        task.setMaxRetries(request.maxRetries());
        task.setStartedAt(request.startedAt());
        task.setFinishedAt(request.finishedAt());
    }

    private boolean isValid(AgentTaskUpsertRequest request) {
        return request != null
                && request.userId() != null
                && request.sourceId() != null
                && StringUtils.hasText(request.taskType())
                && StringUtils.hasText(request.source())
                && StringUtils.hasText(request.status());
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
