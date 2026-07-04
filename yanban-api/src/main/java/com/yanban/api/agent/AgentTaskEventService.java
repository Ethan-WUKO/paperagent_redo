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
        String normalizedTaskType = normalizeTaskType(taskType);
        validateTaskOwnership(userId, normalizedTaskType, taskId, taskType);
        return events.listEvents(normalizedTaskType, taskId, userId).stream()
                .map(AgentTaskEventResponse::from)
                .toList();
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
