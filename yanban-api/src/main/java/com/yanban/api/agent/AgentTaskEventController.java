package com.yanban.api.agent;

import com.yanban.api.security.JwtUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
                                                   @PathVariable Long taskId,
                                                   @RequestParam(required = false) Long afterEventId,
                                                   @RequestParam(required = false) Integer limit) {
        return taskEventService.listEvents(currentUser.id(), taskType, taskId, afterEventId, limit);
    }

    @GetMapping("/{taskType}/{taskId}/events/stream")
    public SseEmitter streamEvents(@AuthenticationPrincipal JwtUser currentUser,
                                   @PathVariable String taskType,
                                   @PathVariable Long taskId,
                                   @RequestParam(required = false) Long afterEventId,
                                   @RequestParam(required = false) Integer limit,
                                   @RequestParam(required = false) Long pollIntervalMs,
                                   @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader) {
        if (afterEventId == null && lastEventIdHeader != null && !lastEventIdHeader.isBlank()) {
            try {
                afterEventId = Long.parseLong(lastEventIdHeader);
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid Last-Event-ID: " + lastEventIdHeader);
            }
        }
        return taskEventService.streamEvents(currentUser.id(), taskType, taskId, afterEventId, limit, pollIntervalMs);
    }
}
