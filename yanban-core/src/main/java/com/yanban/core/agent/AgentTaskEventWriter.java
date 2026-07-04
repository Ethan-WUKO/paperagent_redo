package com.yanban.core.agent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTaskEventWriter {

    private final AgentTaskEventRepository events;

    public AgentTaskEventWriter(AgentTaskEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentTaskEvent record(AgentTaskEventCreateRequest request) {
        return events.save(new AgentTaskEvent(
                request.taskType(),
                request.taskId(),
                request.userId(),
                request.eventType(),
                request.stage(),
                request.status(),
                request.message(),
                request.payloadJson()
        ));
    }
}
