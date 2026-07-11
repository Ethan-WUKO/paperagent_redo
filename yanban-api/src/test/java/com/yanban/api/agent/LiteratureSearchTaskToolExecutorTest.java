package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.literature.LiteratureSearchTaskRequest;
import com.yanban.paper.literature.LiteratureSearchTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class LiteratureSearchTaskToolExecutorTest {

    private static final Long USER_ID = 11L;
    private static final Long TASK_ID = 101L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LiteratureSearchTaskService taskService;
    private LiteratureSearchTaskToolSupport support;

    @BeforeEach
    void setUp() {
        taskService = mock(LiteratureSearchTaskService.class);
        support = new LiteratureSearchTaskToolSupport(taskService, objectMapper);
        when(taskService.isTerminal(LiteratureSearchTaskService.STATUS_PENDING)).thenReturn(false);
        when(taskService.isTerminal(LiteratureSearchTaskService.STATUS_RUNNING)).thenReturn(false);
        when(taskService.isTerminal(LiteratureSearchTaskService.STATUS_CANCEL_REQUESTED)).thenReturn(false);
        when(taskService.isTerminal(LiteratureSearchTaskService.STATUS_COMPLETED)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void registersLiteratureTaskTools() {
        ToolRegistry registry = new ToolRegistry()
                .register(new LiteratureSearchStartToolExecutor(support, objectMapper))
                .register(new LiteratureSearchStatusToolExecutor(support, objectMapper))
                .register(new LiteratureSearchResultToolExecutor(support, objectMapper))
                .register(new LiteratureSearchCancelToolExecutor(support, objectMapper));

        assertThat(registry.listToolNames())
                .containsExactlyInAnyOrder("literature_search_start", "literature_search_status", "literature_search_result", "literature_search_cancel");
        assertThat(registry.find("literature_search_start").orElseThrow().definition().parameters().path("required").get(0).asText())
                .isEqualTo("query");
        assertThat(registry.find("literature_search_status").orElseThrow().definition().parameters().path("required").get(0).asText())
                .isEqualTo("taskId");
    }

    @Test
    void startCreatesCurrentUsersLiteratureTask() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_PENDING);
        when(taskService.createTask(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.any(LiteratureSearchTaskRequest.class)))
                .thenReturn(new LiteratureSearchTaskService.TaskStartResult(task, false));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "hybrid RAG");
        args.put("topK", 5);
        args.put("clientRequestId", "req-1");
        ToolResult result = new LiteratureSearchStartToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-1", "literature_search_start", args));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("taskId").asLong()).isEqualTo(TASK_ID);
        assertThat(result.output().path("status").asText()).isEqualTo("PENDING");
        assertThat(result.output().path("idempotent").asBoolean()).isFalse();
        verify(taskService).createTask(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.any(LiteratureSearchTaskRequest.class));
    }

    @Test
    void resultReturnsStoredItemsAndSourceFailures() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_COMPLETED);
        task.setRawCandidateCount(3);
        task.setUniqueCandidateCount(2);
        task.setSourceAttempts(1);
        task.setResultJson("{\"items\":[{\"title\":\"Hybrid Retrieval for RAG\",\"doi\":\"10.1000/rag\"}]}");
        task.setSourceFailuresJson("[\"openalex: timeout\"]");
        when(taskService.getTask(USER_ID, TASK_ID)).thenReturn(task);

        ToolResult result = new LiteratureSearchResultToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-2", "literature_search_result", args(TASK_ID)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("resultAvailable").asBoolean()).isTrue();
        assertThat(result.output().path("partialResultAvailable").asBoolean()).isTrue();
        assertThat(result.output().path("items").get(0).path("title").asText()).isEqualTo("Hybrid Retrieval for RAG");
        assertThat(result.output().path("sourceFailures").get(0).asText()).contains("timeout");
    }

    @Test
    void cancelUsesServiceAndReportsAcceptedState() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        LiteratureSearchTask before = task(LiteratureSearchTaskService.STATUS_RUNNING);
        LiteratureSearchTask after = task(LiteratureSearchTaskService.STATUS_CANCEL_REQUESTED);
        when(taskService.getTask(USER_ID, TASK_ID)).thenReturn(before);
        when(taskService.requestCancel(USER_ID, TASK_ID, "stop")).thenReturn(after);

        ObjectNode args = args(TASK_ID);
        args.put("cancelReason", "stop");
        ToolResult result = new LiteratureSearchCancelToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-3", "literature_search_cancel", args));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("cancelAccepted").asBoolean()).isTrue();
        assertThat(result.output().path("idempotent").asBoolean()).isFalse();
        verify(taskService).requestCancel(USER_ID, TASK_ID, "stop");
    }

    @Test
    void missingUserContextReturnsFailure() {
        ToolResult result = new LiteratureSearchStatusToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-4", "literature_search_status", args(TASK_ID)));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("缺少当前用户上下文");
    }

    @Test
    void inaccessibleTaskReturnsFailureWithoutOutput() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        when(taskService.getTask(USER_ID, TASK_ID)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "文献检索任务不存在或不可访问"));

        ToolResult result = new LiteratureSearchStatusToolExecutor(support, objectMapper)
                .execute(new ToolCall("call-5", "literature_search_status", args(TASK_ID)));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("不可访问");
        assertThat(result.output()).isNull();
    }

    private ObjectNode args(Long taskId) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("taskId", taskId);
        return arguments;
    }

    private LiteratureSearchTask task(String status) {
        LiteratureSearchTask task = new LiteratureSearchTask(
                USER_ID,
                null,
                "hybrid RAG",
                "hybrid rag",
                8,
                2020,
                true,
                status,
                "QUEUED",
                "req-1",
                "idem"
        );
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        return task;
    }
}
