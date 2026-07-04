package com.yanban.paper.service;

import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskClarification;
import com.yanban.paper.domain.PaperTaskClarificationRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.latex.StructureClarificationCandidate;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaperClarificationService {

    private final PaperTaskRepository tasks;
    private final PaperTaskClarificationRepository clarifications;
    private final PaperEventStreamService eventStreamService;
    private final PaperOrchestrator paperOrchestrator;

    public PaperClarificationService(PaperTaskRepository tasks,
                                     PaperTaskClarificationRepository clarifications,
                                     PaperEventStreamService eventStreamService,
                                     @Lazy PaperOrchestrator paperOrchestrator) {
        this.tasks = tasks;
        this.clarifications = clarifications;
        this.eventStreamService = eventStreamService;
        this.paperOrchestrator = paperOrchestrator;
    }

    @Transactional
    public List<PaperTaskClarification> createPendingClarifications(Long taskId, List<StructureClarificationCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<PaperTaskClarification> saved = candidates.stream()
                .map(candidate -> new PaperTaskClarification(
                        taskId,
                        candidate.type(),
                        toQuestionJson(candidate),
                        toOptionsJson(candidate.options(), candidate.defaultOption()),
                        "PENDING"
                ))
                .map(clarifications::save)
                .toList();
        if (candidates.stream().anyMatch(StructureClarificationCandidate::blocking)) {
            PaperTask task = tasks.findById(taskId).orElseThrow();
            task.setStatus("WAITING_INPUT");
            task.setCurrentStage("STRUCTURE_CHECK");
            tasks.save(task);
            eventStreamService.publish(PaperSseEvent.of("clarification_needed", taskId, "论文结构存在需要确认的问题", "STRUCTURE_CHECK"));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PaperTaskClarification> list(Long userId, Long taskId) {
        getOwnedTask(userId, taskId);
        return clarifications.findByTaskIdOrderByCreatedAtAsc(taskId);
    }

    @Transactional
    public PaperTaskClarification answer(Long userId, Long taskId, Long clarificationId, String answerJson) {
        PaperTask task = getOwnedTask(userId, taskId);
        PaperTaskClarification clarification = clarifications.findById(clarificationId)
                .filter(item -> item.getTaskId().equals(taskId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "澄清问题不存在"));
        if (!"PENDING".equals(clarification.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "澄清问题已回答");
        }
        clarification.answer(answerJson == null || answerJson.isBlank() ? "{}" : answerJson);
        PaperTaskClarification saved = clarifications.save(clarification);
        if (!clarifications.existsByTaskIdAndStatus(taskId, "PENDING") && "WAITING_INPUT".equals(task.getStatus())) {
            task.setStatus("RUNNING");
            task.setCurrentStage("STRUCTURE_CHECK");
            tasks.save(task);
            eventStreamService.publish(PaperSseEvent.of("clarification_resolved", taskId, "结构确认已完成，任务自动继续", task.getCurrentStage()));
            runAfterCommit(() -> paperOrchestrator.startTask(taskId));
        }
        return saved;
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private PaperTask getOwnedTask(Long userId, Long taskId) {
        return tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在"));
    }

    private String toQuestionJson(StructureClarificationCandidate candidate) {
        return "{"
                + "\"type\":\"" + json(candidate.type()) + "\","
                + "\"blocking\":" + candidate.blocking() + ","
                + "\"message\":\"" + json(candidate.message()) + "\","
                + "\"relatedSectionOrderIndex\":" + candidate.relatedSectionOrderIndex()
                + "}";
    }

    private String toOptionsJson(List<String> options, String defaultOption) {
        String joined = options.stream()
                .map(option -> "\"" + json(option) + "\"")
                .collect(Collectors.joining(","));
        return "{\"options\":[" + joined + "],\"defaultOption\":\"" + json(defaultOption) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
