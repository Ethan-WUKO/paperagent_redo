package com.yanban.api.agent;

import com.yanban.api.security.JwtUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskControlController {

    private final TaskControlService taskControlService;

    public TaskControlController(TaskControlService taskControlService) {
        this.taskControlService = taskControlService;
    }

    @PostMapping({"/api/tasks/{taskId}/cancel", "/api/v1/tasks/{taskId}/cancel", "/api/v1/agent/tasks/{taskId}/cancel"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskCancelResponse cancelTask(
            @AuthenticationPrincipal JwtUser currentUser,
            @org.springframework.web.bind.annotation.PathVariable Long taskId,
            @RequestParam(required = false) String taskType,
            @Valid @RequestBody(required = false) TaskCancelRequest request) {
        String effectiveTaskType = request == null || request.taskType() == null || request.taskType().isBlank()
                ? taskType
                : request.taskType();
        String cancelReason = request == null ? null : request.cancelReason();
        return taskControlService.cancel(currentUser.id(), taskId, effectiveTaskType, cancelReason);
    }

    @GetMapping({"/api/tasks/{taskId}/status", "/api/v1/tasks/{taskId}/status", "/api/v1/agent/tasks/{taskId}/status"})
    public TaskStatusResponse getTaskStatus(
            @AuthenticationPrincipal JwtUser currentUser,
            @org.springframework.web.bind.annotation.PathVariable Long taskId,
            @RequestParam(required = false) String taskType) {
        return taskControlService.getStatus(currentUser.id(), taskId, taskType);
    }
}
