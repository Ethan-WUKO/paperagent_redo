package com.yanban.paper.literature;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.paper.domain.LiteratureSearchTask;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LiteratureSearchTaskWorkerTest {

    private static final Long USER_ID = 11L;
    private static final Long TASK_ID = 101L;

    private LiteratureSearchTaskService taskService;
    private AdHocLiteratureSearchService searchService;
    private LiteratureSearchTaskResultMaterializer resultMaterializer;
    private LiteratureSearchTaskWorker worker;

    @BeforeEach
    void setUp() {
        taskService = mock(LiteratureSearchTaskService.class);
        searchService = mock(AdHocLiteratureSearchService.class);
        resultMaterializer = mock(LiteratureSearchTaskResultMaterializer.class);
        worker = new LiteratureSearchTaskWorker(taskService, searchService, resultMaterializer, Runnable::run);
    }

    @Test
    void processRunsSearchAndPersistsResult() throws Exception {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_RUNNING);
        when(taskService.claimForRun(TASK_ID)).thenReturn(Optional.of(task));
        when(taskService.isCancellationRequested(USER_ID, TASK_ID)).thenReturn(false, false);
        when(searchService.search("hybrid RAG", 8, 2020)).thenReturn(result());

        worker.process(TASK_ID);

        verify(resultMaterializer).materializeAndSave(eq(USER_ID), eq(TASK_ID), any());
    }

    @Test
    void processMarksFailedWhenSearchThrows() {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_RUNNING);
        when(taskService.claimForRun(TASK_ID)).thenReturn(Optional.of(task));
        when(taskService.isCancellationRequested(USER_ID, TASK_ID)).thenReturn(false, false);
        when(searchService.search("hybrid RAG", 8, 2020)).thenThrow(new IllegalStateException("source down"));

        worker.process(TASK_ID);

        verify(taskService).markFailed(USER_ID, TASK_ID, "source down");
    }

    @Test
    void processStopsBeforeSearchWhenCancelled() {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_RUNNING);
        when(taskService.claimForRun(TASK_ID)).thenReturn(Optional.of(task));
        when(taskService.isCancellationRequested(USER_ID, TASK_ID)).thenReturn(true);

        worker.process(TASK_ID);

        verify(taskService).markCancelled(USER_ID, TASK_ID);
        verify(searchService, never()).search("hybrid RAG", 8, 2020);
    }

    @Test
    void processDoesNotSaveResultWhenCancelledAfterSearch() throws Exception {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_RUNNING);
        when(taskService.claimForRun(TASK_ID)).thenReturn(Optional.of(task));
        when(taskService.isCancellationRequested(USER_ID, TASK_ID)).thenReturn(false, true);
        when(searchService.search("hybrid RAG", 8, 2020)).thenReturn(result());

        worker.process(TASK_ID);

        verify(taskService).markCancelled(USER_ID, TASK_ID);
        verify(resultMaterializer, never()).materializeAndSave(eq(USER_ID), eq(TASK_ID), any());
    }

    private AdHocLiteratureSearchService.AdHocLiteratureSearchResult result() {
        return new AdHocLiteratureSearchService.AdHocLiteratureSearchResult(
                "hybrid RAG",
                List.of(new AdHocLiteratureSearchService.AdHocLiteratureItem(
                        "Hybrid Retrieval for RAG",
                        List.of("A. Author"),
                        2024,
                        "Demo Journal",
                        "10.1000/rag",
                        null,
                        null,
                        "https://example.test/rag",
                        "abstract",
                        "openalex",
                        "hybrid RAG",
                        0.91,
                        "@article{rag}"
                )),
                2,
                1,
                1,
                List.of("openalex: timeout")
        );
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
                "SEARCHING",
                "req-1",
                "idem"
        );
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        return task;
    }
}
