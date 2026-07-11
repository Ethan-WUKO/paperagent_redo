package com.yanban.api.agent;

import com.yanban.core.agent.AgentTask;
import com.yanban.core.agent.AgentTaskEvent;
import com.yanban.core.agent.AgentTaskEventRepository;
import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.core.agent.AgentTaskRegistry;
import com.yanban.core.agent.AgentTaskRepository;
import com.yanban.core.agent.AgentTaskStatus;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentTaskService {

    private final AgentTaskRepository agentTasks;
    private final AgentTaskEventRepository taskEvents;
    private final PaperTaskRepository paperTasks;
    private final PaperTaskArtifactRepository paperArtifacts;
    private final LiteratureSearchTaskRepository literatureTasks;

    public AgentTaskService(AgentTaskRepository agentTasks,
                            AgentTaskEventRepository taskEvents,
                            PaperTaskRepository paperTasks,
                            PaperTaskArtifactRepository paperArtifacts,
                            LiteratureSearchTaskRepository literatureTasks) {
        this.agentTasks = agentTasks;
        this.taskEvents = taskEvents;
        this.paperTasks = paperTasks;
        this.paperArtifacts = paperArtifacts;
        this.literatureTasks = literatureTasks;
    }

    public TaskStatusResponse getStatus(Long userId, Long taskId, String requestedTaskType) {
        TaskType taskType = resolveTaskType(requestedTaskType);
        if (taskType != null) {
            return getStatusByType(userId, taskId, taskType);
        }

        List<AgentTask> mirrors = agentTasks.findBySourceIdAndUserIdAndTaskTypeIn(
                taskId,
                userId,
                List.of(
                        AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH,
                        AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH
                )
        );
        if (mirrors.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "taskId matches both paper and literature task types; please specify taskType");
        }
        if (mirrors.size() == 1) {
            return getStatusByType(userId, taskId, fromMirrorTaskType(mirrors.get(0).getTaskType()));
        }

        PaperTask paperTask = paperTasks.findByIdAndUserId(taskId, userId).orElse(null);
        LiteratureSearchTask literatureTask = literatureTasks.findByIdAndUserId(taskId, userId).orElse(null);
        if (paperTask != null && literatureTask != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "taskId matches both paper and literature task types; please specify taskType");
        }
        if (paperTask != null) {
            return statusFromPaperLegacy(paperTask, null);
        }
        if (literatureTask != null) {
            return statusFromLiteratureLegacy(literatureTask, null);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
    }

    private TaskStatusResponse getStatusByType(Long userId, Long taskId, TaskType taskType) {
        Optional<AgentTask> mirror = findMirror(userId, taskId, taskType);
        return switch (taskType) {
            case PAPER_POLISH -> {
                PaperTask paperTask = paperTasks.findByIdAndUserId(taskId, userId).orElse(null);
                if (paperTask != null) {
                    yield statusFromPaperLegacy(paperTask, mirror.orElse(null));
                }
                if (mirror.isPresent()) {
                    yield statusFromMirror(mirror.get(), "paper_polish");
                }
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "paper task not found");
            }
            case LITERATURE_SEARCH -> {
                LiteratureSearchTask literatureTask = literatureTasks.findByIdAndUserId(taskId, userId).orElse(null);
                if (literatureTask != null) {
                    yield statusFromLiteratureLegacy(literatureTask, mirror.orElse(null));
                }
                if (mirror.isPresent()) {
                    yield statusFromMirror(mirror.get(), "literature_search");
                }
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "literature search task not found");
            }
        };
    }

    private Optional<AgentTask> findMirror(Long userId, Long taskId, TaskType taskType) {
        return agentTasks.findByTaskTypeAndSourceAndSourceIdAndUserId(
                toMirrorTaskType(taskType),
                toMirrorSource(taskType),
                taskId,
                userId
        );
    }

    private TaskStatusResponse statusFromPaperLegacy(PaperTask task, AgentTask mirror) {
        ArtifactSummary artifacts = summarizeArtifacts(task.getId());
        String status = valueOrMirror(task.getStatus(), mirror == null ? null : mirror.getStatus());
        String currentStage = valueOrMirror(task.getCurrentStage(), mirror == null ? null : mirror.getCurrentStage());
        String errorMessage = valueOrMirror(task.getErrorMessage(), mirror == null ? null : mirror.getErrorMessage());
        LastTaskEvent lastEvent = lastEvent(AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH, task.getId(), task.getUserId());
        Instant startedAt = mirror == null ? null : mirror.getStartedAt();
        Instant finishedAt = mirror == null ? null : mirror.getFinishedAt();
        Integer progressPercent = mirror == null ? null : mirror.getProgressPercent();
        return new TaskStatusResponse(
                "paper_polish",
                task.getId(),
                status,
                currentStage,
                task.getCreatedAt(),
                task.getUpdatedAt(),
                startedAt,
                finishedAt,
                progressPercent,
                mirror == null ? null : mirror.getErrorCode(),
                errorMessage,
                mirror == null ? null : mirror.getCancellationReason(),
                artifacts.partialArtifactCount() > 0,
                artifacts.completedArtifactCount(),
                artifacts.partialArtifactCount(),
                lastEvent.id(),
                lastEvent.eventType(),
                lastEvent.message(),
                lastEvent.createdAt(),
                AgentTaskStatus.isTerminal(status),
                AgentTaskStatus.canCancel(status)
        );
    }

    private TaskStatusResponse statusFromLiteratureLegacy(LiteratureSearchTask task, AgentTask mirror) {
        String status = valueOrMirror(task.getStatus(), mirror == null ? null : mirror.getStatus());
        String currentStage = valueOrMirror(task.getCurrentStage(), mirror == null ? null : mirror.getCurrentStage());
        String errorMessage = valueOrMirror(task.getErrorMessage(), mirror == null ? null : mirror.getErrorMessage());
        String cancellationReason = valueOrMirror(task.getCancelReason(), mirror == null ? null : mirror.getCancellationReason());
        LastTaskEvent lastEvent = lastEvent(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH, task.getId(), task.getUserId());
        Integer progressPercent = mirror != null && mirror.getProgressPercent() != null
                ? mirror.getProgressPercent()
                : (AgentTaskStatus.COMPLETED.value().equals(status) ? 100 : null);
        boolean partialResultAvailable = StringUtils.hasText(task.getResultJson())
                && StringUtils.hasText(task.getSourceFailuresJson());
        return new TaskStatusResponse(
                "literature_search",
                task.getId(),
                status,
                currentStage,
                task.getCreatedAt(),
                task.getUpdatedAt(),
                firstNonNull(task.getStartedAt(), mirror == null ? null : mirror.getStartedAt()),
                firstNonNull(task.getFinishedAt(), mirror == null ? null : mirror.getFinishedAt()),
                progressPercent,
                mirror == null ? null : mirror.getErrorCode(),
                errorMessage,
                cancellationReason,
                partialResultAvailable,
                0,
                0,
                lastEvent.id(),
                lastEvent.eventType(),
                lastEvent.message(),
                lastEvent.createdAt(),
                AgentTaskStatus.isTerminal(status),
                AgentTaskStatus.canCancel(status)
        );
    }

    private TaskStatusResponse statusFromMirror(AgentTask task, String responseTaskType) {
        LastTaskEvent lastEvent = lastEvent(task.getTaskType(), task.getSourceId(), task.getUserId());
        return new TaskStatusResponse(
                responseTaskType,
                task.getSourceId(),
                task.getStatus(),
                task.getCurrentStage(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getProgressPercent(),
                task.getErrorCode(),
                task.getErrorMessage(),
                task.getCancellationReason(),
                false,
                0,
                0,
                lastEvent.id(),
                lastEvent.eventType(),
                lastEvent.message(),
                lastEvent.createdAt(),
                AgentTaskStatus.isTerminal(task.getStatus()),
                AgentTaskStatus.canCancel(task.getStatus())
        );
    }

    private ArtifactSummary summarizeArtifacts(Long taskId) {
        int completed = 0;
        int partial = 0;
        for (PaperTaskArtifact artifact : paperArtifacts.findByTaskIdOrderByCreatedAt(taskId)) {
            if (PaperTaskArtifact.STATUS_COMPLETED.equals(artifact.getArtifactStatus())) {
                completed++;
            } else if (PaperTaskArtifact.STATUS_PARTIAL.equals(artifact.getArtifactStatus())) {
                partial++;
            }
        }
        return new ArtifactSummary(completed, partial);
    }

    private TaskType resolveTaskType(String taskType) {
        if (!StringUtils.hasText(taskType)) {
            return null;
        }
        String normalized = taskType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PAPER", "PAPER_POLISH", "PAPER_TASK", "PAPERTASK" -> TaskType.PAPER_POLISH;
            case "LITERATURE", "LITERATURE_SEARCH", "LITERATURE_TASK", "LITERATURETASK" -> TaskType.LITERATURE_SEARCH;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported taskType: " + taskType);
        };
    }

    private TaskType fromMirrorTaskType(String taskType) {
        if (AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH.equals(taskType)) {
            return TaskType.PAPER_POLISH;
        }
        if (AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH.equals(taskType)) {
            return TaskType.LITERATURE_SEARCH;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported taskType: " + taskType);
    }

    private String toMirrorTaskType(TaskType taskType) {
        return switch (taskType) {
            case PAPER_POLISH -> AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH;
            case LITERATURE_SEARCH -> AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH;
        };
    }

    private String toMirrorSource(TaskType taskType) {
        return switch (taskType) {
            case PAPER_POLISH -> AgentTaskRegistry.SOURCE_PAPER_TASK;
            case LITERATURE_SEARCH -> AgentTaskRegistry.SOURCE_LITERATURE_SEARCH_TASK;
        };
    }

    private String valueOrMirror(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private Instant firstNonNull(Instant preferred, Instant fallback) {
        return preferred != null ? preferred : fallback;
    }

    private LastTaskEvent lastEvent(String taskType, Long taskId, Long userId) {
        if (!StringUtils.hasText(taskType) || taskId == null || userId == null) {
            return LastTaskEvent.empty();
        }
        return taskEvents.findTopByTaskTypeAndTaskIdAndUserIdOrderByIdDesc(taskType, taskId, userId)
                .map(LastTaskEvent::from)
                .orElseGet(LastTaskEvent::empty);
    }

    private record ArtifactSummary(int completedArtifactCount, int partialArtifactCount) {
    }

    private record LastTaskEvent(Long id, String eventType, String message, Instant createdAt) {
        private static LastTaskEvent from(AgentTaskEvent event) {
            return new LastTaskEvent(event.getId(), event.getEventType(), event.getMessage(), event.getCreatedAt());
        }

        private static LastTaskEvent empty() {
            return new LastTaskEvent(null, null, null, null);
        }
    }

    private enum TaskType {
        PAPER_POLISH,
        LITERATURE_SEARCH
    }
}
