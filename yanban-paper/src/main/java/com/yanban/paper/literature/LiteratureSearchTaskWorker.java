package com.yanban.paper.literature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.service.PaperModelExecutionContext;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class LiteratureSearchTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(LiteratureSearchTaskWorker.class);

    private final LiteratureSearchTaskService taskService;
    private final AdHocLiteratureSearchService searchService;
    private final LiteratureSearchTaskResultMaterializer resultMaterializer;
    private final Executor literatureTaskExecutor;

    public LiteratureSearchTaskWorker(LiteratureSearchTaskService taskService,
                                      AdHocLiteratureSearchService searchService,
                                      LiteratureSearchTaskResultMaterializer resultMaterializer,
                                      @Qualifier("literatureTaskExecutor") Executor literatureTaskExecutor) {
        this.taskService = taskService;
        this.searchService = searchService;
        this.resultMaterializer = resultMaterializer;
        this.literatureTaskExecutor = literatureTaskExecutor;
    }

    public void submit(Long taskId) {
        literatureTaskExecutor.execute(() -> process(taskId));
    }

    public void process(Long taskId) {
        Optional<LiteratureSearchTask> claimed = taskService.claimForRun(taskId);
        if (claimed.isEmpty()) {
            return;
        }
        LiteratureSearchTask task = claimed.get();
        Long userId = task.getUserId();
        try {
            if (taskService.isCancellationRequested(userId, taskId)) {
                taskService.markCancelled(userId, taskId);
                return;
            }
            AdHocLiteratureSearchService.AdHocLiteratureSearchResult result;
            try (PaperModelExecutionContext.Scope ignored = PaperModelExecutionContext.open(userId)) {
                result = searchService.search(task.getQuery(), task.getTopK(), task.getYearFrom());
            }
            if (taskService.isCancellationRequested(userId, taskId)) {
                taskService.markCancelled(userId, taskId);
                return;
            }
            resultMaterializer.materializeAndSave(userId, taskId, result);
        } catch (LiteratureSearchTaskResultMaterializer.LiteratureSearchTaskCancelledException ex) {
            taskService.markCancelled(userId, taskId);
        } catch (Exception ex) {
            if (taskService.isCancellationRequested(userId, taskId)) {
                taskService.markCancelled(userId, taskId);
                return;
            }
            log.warn("文献检索任务执行失败 taskId={} userId={}", taskId, userId, ex);
            taskService.markFailed(userId, taskId, failureMessage(ex));
        }
    }

    private String failureMessage(Exception ex) {
        if (ex instanceof JsonProcessingException jsonEx) {
            return "结果序列化失败: " + jsonEx.getOriginalMessage();
        }
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
