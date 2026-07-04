package com.yanban.api.agent;

import com.yanban.core.agent.AgentTaskEvent;
import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.literature.LiteratureSearchTaskService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentTaskEventService {

    private static final int DEFAULT_INCREMENTAL_LIMIT = 100;
    private static final int MAX_INCREMENTAL_LIMIT = 500;
    private static final int DEFAULT_STREAM_POLL_INTERVAL_MS = 1000;
    private static final int MIN_STREAM_POLL_INTERVAL_MS = 250;
    private static final int MAX_STREAM_POLL_INTERVAL_MS = 30_000;
    private static final Set<String> TERMINAL_EVENT_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT");
    private static final Set<String> TERMINAL_EVENT_TYPES = Set.of(
            "TASK_COMPLETED",
            "TASK_FAILED",
            "TASK_CANCELLED",
            "TASK_TIMED_OUT"
    );

    private final AgentTaskEventRecorder events;
    private final LiteratureSearchTaskService literatureTasks;
    private final PaperTaskRepository paperTasks;
    private final ExecutorService eventStreamExecutor;

    public AgentTaskEventService(AgentTaskEventRecorder events,
                                 LiteratureSearchTaskService literatureTasks,
                                 PaperTaskRepository paperTasks) {
        this.events = events;
        this.literatureTasks = literatureTasks;
        this.paperTasks = paperTasks;
        this.eventStreamExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "agent-task-event-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    public List<AgentTaskEventResponse> listEvents(Long userId, String taskType, Long taskId) {
        return listEvents(userId, taskType, taskId, null, null);
    }

    public List<AgentTaskEventResponse> listEvents(Long userId,
                                                   String taskType,
                                                   Long taskId,
                                                   Long afterEventId,
                                                   Integer limit) {
        String normalizedTaskType = normalizeTaskType(taskType);
        validateCursor(afterEventId, limit);
        validateTaskOwnership(userId, normalizedTaskType, taskId, taskType);
        List<AgentTaskEvent> taskEvents = afterEventId == null && limit == null
                ? events.listEvents(normalizedTaskType, taskId, userId)
                : events.listEvents(normalizedTaskType, taskId, userId, afterEventId, normalizeLimit(limit));
        return taskEvents.stream()
                .map(AgentTaskEventResponse::from)
                .toList();
    }

    public SseEmitter streamEvents(Long userId,
                                   String taskType,
                                   Long taskId,
                                   Long afterEventId,
                                   Integer limit,
                                   Long pollIntervalMs) {
        String normalizedTaskType = normalizeTaskType(taskType);
        validateCursor(afterEventId, limit);
        validateTaskOwnership(userId, normalizedTaskType, taskId, taskType);
        validatePollingInterval(pollIntervalMs);

        int normalizedLimit = normalizeLimit(limit);
        long normalizedPollIntervalMs = normalizePollInterval(pollIntervalMs);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong cursor = new AtomicLong(afterEventId == null ? 0L : afterEventId);

        emitter.onCompletion(() -> running.set(false));
        emitter.onTimeout(() -> running.set(false));
        emitter.onError(ex -> running.set(false));

        eventStreamExecutor.submit(() -> {
            try {
                streamHistoricalAndFollowedEvents(
                        emitter,
                        userId,
                        normalizedTaskType,
                        taskId,
                        cursor,
                        normalizedLimit,
                        normalizedPollIntervalMs,
                        running
                );
            } catch (Throwable error) {
                completeOnError(emitter, running, error);
            }
        });
        return emitter;
    }

    private void streamHistoricalAndFollowedEvents(SseEmitter emitter,
                                                  Long userId,
                                                  String normalizedTaskType,
                                                  Long taskId,
                                                  AtomicLong cursor,
                                                  int limit,
                                                  long pollIntervalMs,
                                                  AtomicBoolean running) {
        try {
            List<AgentTaskEventResponse> initialEvents = events.listEvents(normalizedTaskType, taskId, userId).stream()
                    .map(AgentTaskEventResponse::from)
                    .toList();
            if (sendEventsAndCheckTerminal(emitter, running, cursor, initialEvents)) {
                return;
            }

            while (running.get()) {
                List<AgentTaskEventResponse> updates =
                        listEventsByCursor(normalizedTaskType, userId, taskId, cursor.get(), limit);
                if (sendEventsAndCheckTerminal(emitter, running, cursor, updates)) {
                    return;
                }
                if (!running.get()) {
                    return;
                }
                if (updates.isEmpty()) {
                    sendHeartbeat(emitter, running);
                }
                if (!running.get()) {
                    return;
                }
                Thread.sleep(pollIntervalMs);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            completeOnError(emitter, running, ex);
        }
    }

    private List<AgentTaskEventResponse> listEventsByCursor(String normalizedTaskType,
                                                           Long userId,
                                                           Long taskId,
                                                           Long cursor,
                                                           int limit) {
        return events.listEvents(normalizedTaskType, taskId, userId, cursor, limit).stream()
                .map(AgentTaskEventResponse::from)
                .toList();
    }

    private boolean sendEventsAndCheckTerminal(SseEmitter emitter,
                                               AtomicBoolean running,
                                               AtomicLong cursor,
                                               List<AgentTaskEventResponse> eventsToSend) throws IOException {
        for (AgentTaskEventResponse event : eventsToSend) {
            if (!running.get() || event.id() == null || event.id() <= cursor.get()) {
                continue;
            }
            cursor.set(event.id());
            emitter.send(SseEmitter.event()
                    .id(event.id().toString())
                    .name("agent-task-event")
                    .data(event));
            if (isTerminalEvent(event)) {
                emitter.complete();
                return true;
            }
        }
        return false;
    }

    private void sendHeartbeat(SseEmitter emitter, AtomicBoolean running) {
        if (!running.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException ex) {
            running.set(false);
        }
    }

    private void completeOnError(SseEmitter emitter, AtomicBoolean running, Throwable error) {
        if (!running.get()) {
            return;
        }
        running.set(false);
        emitter.completeWithError(error);
    }

    private boolean isTerminalEvent(AgentTaskEventResponse event) {
        return TERMINAL_EVENT_STATUSES.contains(event.status()) || TERMINAL_EVENT_TYPES.contains(event.eventType());
    }

    private void validateCursor(Long afterEventId, Integer limit) {
        if (afterEventId != null && afterEventId < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "afterEventId must be >= 0");
        }
        if (limit != null && limit <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be > 0");
        }
    }

    private void validatePollingInterval(Long pollIntervalMs) {
        if (pollIntervalMs != null && pollIntervalMs < MIN_STREAM_POLL_INTERVAL_MS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pollIntervalMs must be >= 250");
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_INCREMENTAL_LIMIT;
        }
        return Math.min(limit, MAX_INCREMENTAL_LIMIT);
    }

    private long normalizePollInterval(Long pollIntervalMs) {
        if (pollIntervalMs == null) {
            return DEFAULT_STREAM_POLL_INTERVAL_MS;
        }
        return Math.min(Math.max(pollIntervalMs, MIN_STREAM_POLL_INTERVAL_MS), MAX_STREAM_POLL_INTERVAL_MS);
    }

    private void validateTaskOwnership(Long userId, String normalizedTaskType, Long taskId, String rawTaskType) {
        if (AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH.equals(normalizedTaskType)) {
            literatureTasks.getTask(userId, taskId);
            return;
        }
        if (AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH.equals(normalizedTaskType)) {
            paperTasks.findByIdAndUserId(taskId, userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "paper task not found"));
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported taskType: " + rawTaskType);
    }

    private String normalizeTaskType(String taskType) {
        if (!StringUtils.hasText(taskType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskType can not be blank");
        }
        return taskType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    @PreDestroy
    public void closeStreamExecutor() {
        eventStreamExecutor.shutdownNow();
    }
}
