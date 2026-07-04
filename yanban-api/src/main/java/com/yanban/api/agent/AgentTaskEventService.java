package com.yanban.api.agent;

import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.literature.LiteratureSearchTaskService;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentTaskEventService {

    private static final int DEFAULT_INCREMENTAL_LIMIT = 100;
    private static final int MAX_INCREMENTAL_LIMIT = 500;

    private final AgentTaskEventRecorder events;
    private final LiteratureSearchTaskService literatureTasks;
    private final PaperTaskRepository paperTasks;

    public AgentTaskEventService(AgentTaskEventRecorder events,
                                 LiteratureSearchTaskService literatureTasks,
                                 PaperTaskRepository paperTasks) {
        this.events = events;
        this.literatureTasks = literatureTasks;
        this.paperTasks = paperTasks;
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
        List<com.yanban.core.agent.AgentTaskEvent> taskEvents = afterEventId == null && limit == null
                ? events.listEvents(normalizedTaskType, taskId, userId)
                : events.listEvents(normalizedTaskType, taskId, userId, afterEventId, normalizeLimit(limit));
        return taskEvents.stream()
                .map(AgentTaskEventResponse::from)
                .toList();
    }

    private void validateCursor(Long afterEventId, Integer limit) {
        if (afterEventId != null && afterEventId < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "afterEventId 不能小于 0");
        }
        if (limit != null && limit <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须大于 0");
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_INCREMENTAL_LIMIT;
        }
        return Math.min(limit, MAX_INCREMENTAL_LIMIT);
    }

    private void validateTaskOwnership(Long userId, String normalizedTaskType, Long taskId, String rawTaskType) {
        if (AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH.equals(normalizedTaskType)) {
            literatureTasks.getTask(userId, taskId);
            return;
        }
        if (AgentTaskEventRecorder.TASK_TYPE_PAPER_POLISH.equals(normalizedTaskType)) {
            paperTasks.findByIdAndUserId(taskId, userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在或不可访问"));
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂不支持该任务类型: " + rawTaskType);
    }

    private String normalizeTaskType(String taskType) {
        if (!StringUtils.hasText(taskType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskType 不能为空");
        }
        return taskType.trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }
}
