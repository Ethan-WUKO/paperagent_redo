package com.yanban.core.agent;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentTaskEventRecorder {

    public static final String TASK_TYPE_LITERATURE_SEARCH = "LITERATURE_SEARCH";
    public static final String TASK_TYPE_PAPER_POLISH = "PAPER_POLISH";

    private static final Logger log = LoggerFactory.getLogger(AgentTaskEventRecorder.class);

    private final AgentTaskEventWriter writer;
    private final AgentTaskEventRepository events;

    public AgentTaskEventRecorder(AgentTaskEventWriter writer, AgentTaskEventRepository events) {
        this.writer = writer;
        this.events = events;
    }

    public void recordSafely(AgentTaskEventCreateRequest request) {
        if (!isRecordable(request)) {
            return;
        }
        try {
            writer.record(request);
        } catch (Exception ex) {
            log.warn("记录任务事件失败 taskType={} taskId={} eventType={}",
                    request.taskType(),
                    request.taskId(),
                    request.eventType(),
                    ex);
        }
    }

    @Transactional(readOnly = true)
    public List<AgentTaskEvent> listEvents(String taskType, Long taskId, Long userId) {
        if (!StringUtils.hasText(taskType) || taskId == null || userId == null) {
            return List.of();
        }
        return events.findByTaskTypeAndTaskIdAndUserIdOrderByCreatedAtAsc(taskType, taskId, userId);
    }

    private boolean isRecordable(AgentTaskEventCreateRequest request) {
        return request != null
                && StringUtils.hasText(request.taskType())
                && request.taskId() != null
                && request.userId() != null
                && StringUtils.hasText(request.eventType());
    }
}
