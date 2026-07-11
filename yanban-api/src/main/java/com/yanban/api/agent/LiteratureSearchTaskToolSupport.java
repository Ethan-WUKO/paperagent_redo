package com.yanban.api.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolResult;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.literature.LiteratureSearchTaskRequest;
import com.yanban.paper.literature.LiteratureSearchTaskService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class LiteratureSearchTaskToolSupport {

    private final LiteratureSearchTaskService taskService;
    private final ObjectMapper objectMapper;

    LiteratureSearchTaskToolSupport(LiteratureSearchTaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    ToolResult start(String toolCallId, String toolName, LiteratureSearchTaskRequest request) {
        return withUser(toolCallId, toolName, userId -> {
            LiteratureSearchTaskService.TaskStartResult result = taskService.createTask(userId, request);
            ObjectNode output = taskSummary(result.task());
            output.put("idempotent", result.idempotent());
            output.put("message", result.idempotent() ? "已返回同一请求对应的文献检索任务" : "文献检索任务已创建");
            return ToolResult.success(toolCallId, toolName, output);
        });
    }

    ToolResult status(String toolCallId, String toolName, Long taskId) {
        return withUser(toolCallId, toolName, userId -> ToolResult.success(toolCallId, toolName, taskSummary(taskService.getTask(userId, taskId))));
    }

    ToolResult result(String toolCallId, String toolName, Long taskId) {
        return withUser(toolCallId, toolName, userId -> {
            LiteratureSearchTask task = taskService.getTask(userId, taskId);
            ObjectNode output = taskSummary(task);
            output.put("resultAvailable", task.getResultJson() != null && !task.getResultJson().isBlank());
            output.put("partialResultAvailable", partialResultAvailable(task));
            output.set("items", resultItems(task.getResultJson()));
            output.set("sourceFailures", sourceFailures(task.getSourceFailuresJson()));
            return ToolResult.success(toolCallId, toolName, output);
        });
    }

    ToolResult cancel(String toolCallId, String toolName, Long taskId, String cancelReason) {
        return withUser(toolCallId, toolName, userId -> {
            LiteratureSearchTask before = taskService.getTask(userId, taskId);
            boolean terminalBefore = taskService.isTerminal(before.getStatus());
            LiteratureSearchTask after = taskService.requestCancel(userId, taskId, cancelReason);
            ObjectNode output = taskSummary(after);
            output.put("cancelAccepted", !terminalBefore);
            output.put("idempotent", terminalBefore);
            output.put("message", terminalBefore ? "任务已是终态，取消请求保持幂等" : "取消请求已受理");
            return ToolResult.success(toolCallId, toolName, output);
        });
    }

    private ObjectNode taskSummary(LiteratureSearchTask task) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("taskId", task.getId());
        output.put("query", task.getQuery());
        output.put("topK", task.getTopK());
        if (task.getYearFrom() != null) {
            output.put("yearFrom", task.getYearFrom());
        }
        output.put("includeBibtex", Boolean.TRUE.equals(task.getIncludeBibtex()));
        output.put("status", task.getStatus());
        output.put("currentStage", task.getCurrentStage());
        if (task.getErrorMessage() != null) {
            output.put("errorMessage", task.getErrorMessage());
        }
        output.put("terminal", taskService.isTerminal(task.getStatus()));
        output.put("cancellable", !taskService.isTerminal(task.getStatus()));
        output.put("clientRequestId", task.getClientRequestId());
        if (task.getProjectId() != null) {
            output.put("projectId", task.getProjectId());
        }
        if (task.getRawCandidateCount() != null) {
            output.put("rawCandidateCount", task.getRawCandidateCount());
        }
        if (task.getUniqueCandidateCount() != null) {
            output.put("uniqueCandidateCount", task.getUniqueCandidateCount());
        }
        if (task.getSourceAttempts() != null) {
            output.put("sourceAttempts", task.getSourceAttempts());
        }
        output.put("partialResultAvailable", partialResultAvailable(task));
        output.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
        output.put("updatedAt", task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString());
        return output;
    }

    private boolean partialResultAvailable(LiteratureSearchTask task) {
        return task.getResultJson() != null && !task.getResultJson().isBlank()
                && task.getSourceFailuresJson() != null && !task.getSourceFailuresJson().isBlank();
    }

    private ArrayNode resultItems(String resultJson) {
        ArrayNode array = objectMapper.createArrayNode();
        if (resultJson == null || resultJson.isBlank()) {
            return array;
        }
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            JsonNode items = root.isArray() ? root : root.path("items");
            if (items.isArray()) {
                items.forEach(array::add);
            }
        } catch (JsonProcessingException ignored) {
            return objectMapper.createArrayNode();
        }
        return array;
    }

    private ArrayNode sourceFailures(String sourceFailuresJson) {
        ArrayNode array = objectMapper.createArrayNode();
        if (sourceFailuresJson == null || sourceFailuresJson.isBlank()) {
            return array;
        }
        try {
            JsonNode root = objectMapper.readTree(sourceFailuresJson);
            if (root.isArray()) {
                root.forEach(array::add);
            }
        } catch (JsonProcessingException ignored) {
            return objectMapper.createArrayNode();
        }
        return array;
    }

    private ToolResult withUser(String toolCallId, String toolName, ToolOperation operation) {
        Long userId = ToolExecutionContext.getCurrentUserId();
        if (userId == null) {
            return ToolResult.failure(toolCallId, toolName, "缺少当前用户上下文，无法访问文献检索任务");
        }
        try {
            return operation.execute(userId);
        } catch (ResponseStatusException ex) {
            HttpStatusCode statusCode = ex.getStatusCode();
            return ToolResult.failure(toolCallId, toolName, ex.getReason() == null ? statusCode.toString() : ex.getReason());
        } catch (RuntimeException ex) {
            return ToolResult.failure(toolCallId, toolName, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface ToolOperation {
        ToolResult execute(Long userId);
    }
}
