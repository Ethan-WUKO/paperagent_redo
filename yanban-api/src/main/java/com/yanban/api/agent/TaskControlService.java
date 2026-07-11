package com.yanban.api.agent;

import com.yanban.core.agent.AgentTaskStatus;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.literature.LiteratureSearchTaskService;
import com.yanban.paper.service.PaperOrchestrator;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskControlService {

    private final PaperTaskRepository paperTasks;
    private final PaperOrchestrator paperOrchestrator;
    private final LiteratureSearchTaskRepository literatureTasks;
    private final LiteratureSearchTaskService literatureTaskService;
    private final AgentTaskService agentTaskService;

    public TaskControlService(PaperTaskRepository paperTasks,
                              PaperOrchestrator paperOrchestrator,
                              LiteratureSearchTaskRepository literatureTasks,
                              LiteratureSearchTaskService literatureTaskService,
                              AgentTaskService agentTaskService) {
        this.paperTasks = paperTasks;
        this.paperOrchestrator = paperOrchestrator;
        this.literatureTasks = literatureTasks;
        this.literatureTaskService = literatureTaskService;
        this.agentTaskService = agentTaskService;
    }

    public TaskCancelResponse cancel(Long userId, Long taskId, String requestedTaskType, String cancelReason) {
        TaskType taskType = resolveTaskType(requestedTaskType);
        if (taskType != null) {
            return switch (taskType) {
                case PAPER_POLISH -> cancelPaperTask(userId, taskId, cancelReason);
                case LITERATURE_SEARCH -> cancelLiteratureTask(userId, taskId, cancelReason);
            };
        }

        return cancelAutoDetect(userId, taskId, cancelReason);
    }

    public TaskStatusResponse getStatus(Long userId, Long taskId, String requestedTaskType) {
        return agentTaskService.getStatus(userId, taskId, requestedTaskType);
    }

    public TaskDispatchRetryResponse retryDelivery(Long userId, Long taskId, String requestedTaskType) {
        TaskType taskType = resolveTaskType(requestedTaskType);
        if (taskType != null) {
            return switch (taskType) {
                case PAPER_POLISH -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "manual delivery retry is only supported for literature search tasks");
                case LITERATURE_SEARCH -> retryLiteratureDelivery(userId, taskId);
            };
        }
        return retryDeliveryAutoDetect(userId, taskId);
    }

    private TaskCancelResponse cancelAutoDetect(Long userId, Long taskId, String cancelReason) {
        PaperTask paperTask = paperTasks.findByIdAndUserId(taskId, userId).orElse(null);
        LiteratureSearchTask literatureTask = literatureTasks.findByIdAndUserId(taskId, userId).orElse(null);

        if (paperTask != null && literatureTask != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "taskId matches both paper and literature task types; please specify taskType");
        }
        if (paperTask != null) {
            return cancelPaperTask(userId, taskId, cancelReason);
        }
        if (literatureTask != null) {
            return cancelLiteratureTask(userId, taskId, cancelReason);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
    }

    private TaskDispatchRetryResponse retryDeliveryAutoDetect(Long userId, Long taskId) {
        PaperTask paperTask = paperTasks.findByIdAndUserId(taskId, userId).orElse(null);
        LiteratureSearchTask literatureTask = literatureTasks.findByIdAndUserId(taskId, userId).orElse(null);

        if (paperTask != null && literatureTask != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "taskId matches both paper and literature task types; please specify taskType");
        }
        if (paperTask != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "manual delivery retry is only supported for literature search tasks");
        }
        if (literatureTask != null) {
            return retryLiteratureDelivery(userId, taskId);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
    }

    private TaskCancelResponse cancelPaperTask(Long userId, Long taskId, String cancelReason) {
        PaperTask task = paperTasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "paper task not found"));
        String beforeStatus = task.getStatus();
        boolean cancelAccepted = canCancel(beforeStatus);
        if (cancelAccepted) {
            paperOrchestrator.stop(userId, taskId, cancelReason);
        }
        PaperTask currentTask = paperTasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "paper task disappeared"));
        return new TaskCancelResponse(
                "paper_polish",
                taskId,
                cancelAccepted,
                !cancelAccepted,
                beforeStatus,
                currentTask.getStatus(),
                currentTask.getCurrentStage(),
                messageForCancel(beforeStatus, cancelAccepted, "paper task")
        );
    }

    private TaskCancelResponse cancelLiteratureTask(Long userId, Long taskId, String cancelReason) {
        LiteratureSearchTask before = literatureTasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "literature search task not found"));
        boolean cancelAccepted = canCancel(before.getStatus());
        LiteratureSearchTask after = cancelAccepted
                ? literatureTaskService.requestCancel(userId, taskId, cancelReason)
                : before;
        return new TaskCancelResponse(
                "literature_search",
                taskId,
                cancelAccepted,
                !cancelAccepted,
                before.getStatus(),
                after.getStatus(),
                after.getCurrentStage(),
                messageForCancel(before.getStatus(), cancelAccepted, "literature search task")
        );
    }

    private TaskDispatchRetryResponse retryLiteratureDelivery(Long userId, Long taskId) {
        LiteratureSearchTask before = literatureTasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "literature search task not found"));
        LiteratureSearchTaskService.DispatchRetryResult result = literatureTaskService.retryPendingDispatch(userId, taskId);
        LiteratureSearchTask after = result.task();
        return new TaskDispatchRetryResponse(
                "literature_search",
                taskId,
                result.retryAccepted(),
                result.idempotent(),
                before.getStatus(),
                after.getStatus(),
                after.getCurrentStage(),
                result.message()
        );
    }

    private boolean isPaperTerminal(String status) {
        return AgentTaskStatus.isTerminal(status);
    }

    private boolean canCancel(String status) {
        return AgentTaskStatus.canCancel(status);
    }

    private boolean isTerminal(String status) {
        return isPaperTerminal(status) || literatureTaskService.isTerminal(status);
    }

    private boolean isCancelling(String status) {
        return AgentTaskStatus.isCancelling(status);
    }

    private String messageForCancel(String status, boolean cancelAccepted, String taskTypeName) {
        if (cancelAccepted) {
            return "cancel requested for " + taskTypeName;
        }
        if (isCancelling(status)) {
            return "cancel already requested";
        }
        return "task already in terminal state";
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

    private enum TaskType {
        PAPER_POLISH,
        LITERATURE_SEARCH
    }
}
