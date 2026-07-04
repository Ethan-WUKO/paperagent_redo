package com.yanban.paper.literature;

import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LiteratureSearchTaskService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_CANCEL_REQUESTED = "CANCEL_REQUESTED";
    public static final String STATUS_CANCELLING = "CANCELLING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private static final Set<String> TERMINAL_STATUSES = Set.of(STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED);

    private final LiteratureSearchTaskRepository tasks;

    public LiteratureSearchTaskService(LiteratureSearchTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Transactional
    public TaskStartResult createTask(Long userId, LiteratureSearchTaskRequest request) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少当前用户上下文");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request 不能为空");
        }
        String query = normalizeQuery(request.query());
        if (!StringUtils.hasText(query)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }
        int topK = clampTopK(request.topK());
        Integer yearFrom = request.yearFrom();
        boolean includeBibtex = request.includeBibtex() == null || request.includeBibtex();
        String clientRequestId = clientRequestId(request.clientRequestId());
        String idempotencyKey = idempotencyKey(userId, query, topK, yearFrom, includeBibtex, clientRequestId);
        return tasks.findByIdempotencyKey(idempotencyKey)
                .map(task -> new TaskStartResult(task, true))
                .orElseGet(() -> new TaskStartResult(tasks.save(new LiteratureSearchTask(
                                userId,
                                request.projectId(),
                                query,
                                query.toLowerCase(Locale.ROOT),
                                topK,
                                yearFrom,
                                includeBibtex,
                                STATUS_PENDING,
                                "QUEUED",
                                clientRequestId,
                                idempotencyKey
                        )),
                        false));
    }

    @Transactional(readOnly = true)
    public LiteratureSearchTask getTask(Long userId, Long taskId) {
        return ownedTask(userId, taskId);
    }

    @Transactional
    public LiteratureSearchTask requestCancel(Long userId, Long taskId, String cancelReason) {
        LiteratureSearchTask task = ownedTask(userId, taskId);
        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            return task;
        }
        task.setStatus(STATUS_CANCEL_REQUESTED);
        task.setCurrentStage("CANCEL_REQUESTED");
        task.setCancelReason(trimToNull(cancelReason));
        return tasks.save(task);
    }

    @Transactional
    public LiteratureSearchTask saveResult(Long userId,
                                           Long taskId,
                                           String resultJson,
                                           Integer rawCandidateCount,
                                           Integer uniqueCandidateCount,
                                           Integer sourceAttempts,
                                           String sourceFailuresJson) {
        LiteratureSearchTask task = ownedTask(userId, taskId);
        task.setResultJson(resultJson);
        task.setRawCandidateCount(rawCandidateCount);
        task.setUniqueCandidateCount(uniqueCandidateCount);
        task.setSourceAttempts(sourceAttempts);
        task.setSourceFailuresJson(sourceFailuresJson);
        task.setStatus(STATUS_COMPLETED);
        task.setCurrentStage("COMPLETE");
        task.setFinishedAt(Instant.now());
        return tasks.save(task);
    }

    public boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(status);
    }

    private LiteratureSearchTask ownedTask(Long userId, Long taskId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少当前用户上下文");
        }
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId 不能为空");
        }
        return tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文献检索任务不存在或不可访问"));
    }

    private int clampTopK(Integer topK) {
        int value = topK == null ? 8 : topK;
        return Math.min(Math.max(value, 1), 20);
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.replaceAll("\\s+", " ").trim();
    }

    private String clientRequestId(String value) {
        return StringUtils.hasText(value) ? value.trim() : UUID.randomUUID().toString();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String idempotencyKey(Long userId, String query, int topK, Integer yearFrom, boolean includeBibtex, String clientRequestId) {
        String raw = userId + "|LITERATURE_SEARCH|" + query.toLowerCase(Locale.ROOT) + "|" + topK + "|"
                + (yearFrom == null ? "" : yearFrom) + "|" + includeBibtex + "|" + clientRequestId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build literature task idempotency key", ex);
        }
    }

    public record TaskStartResult(LiteratureSearchTask task, boolean idempotent) {
    }
}
