package com.yanban.api.agent;

import com.yanban.api.security.JwtUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/tasks")
public class AgentTaskEventController {

    private final AgentTaskEventService taskEventService;

    public AgentTaskEventController(AgentTaskEventService taskEventService) {
        this.taskEventService = taskEventService;
    }

    @GetMapping("/{taskType}/{taskId}/events")
    public List<AgentTaskEventResponse> listEvents(@AuthenticationPrincipal JwtUser currentUser,
                                                   @PathVariable String taskType,
                                                   @PathVariable Long taskId) {
        return taskEventService.listEvents(currentUser.id(), taskType, taskId);
    }
}
