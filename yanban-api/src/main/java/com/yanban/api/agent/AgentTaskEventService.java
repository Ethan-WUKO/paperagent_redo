package com.yanban.api.agent;

import com.yanban.core.agent.AgentTaskEventRecorder;
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

    public AgentTaskEventService(AgentTaskEventRecorder events, LiteratureSearchTaskService literatureTasks) {
        this.events = events;
        this.literatureTasks = literatureTasks;
    }

    public List<AgentTaskEventResponse> listEvents(Long userId, String taskType, Long taskId) {
        String normalizedTaskType = normalizeTaskType(taskType);
        if (!AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH.equals(normalizedTaskType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂不支持该任务类型: " + taskType);
        }
        literatureTasks.getTask(userId, taskId);
        return events.listEvents(normalizedTaskType, taskId, userId).stream()
                .map(AgentTaskEventResponse::from)
                .toList();
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
