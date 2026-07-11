package com.yanban.api.literature;

import com.yanban.paper.literature.LiteratureSearchTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(LiteratureSearchTaskScannerProperties.class)
public class LiteratureSearchTaskScanner {

    private static final Logger log = LoggerFactory.getLogger(LiteratureSearchTaskScanner.class);

    private final LiteratureSearchTaskService taskService;
    private final LiteratureSearchTaskScannerProperties properties;

    public LiteratureSearchTaskScanner(LiteratureSearchTaskService taskService,
                                       LiteratureSearchTaskScannerProperties properties) {
        this.taskService = taskService;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${yanban.literature.task.scanner.initial-delay:30000}",
            fixedDelayString = "${yanban.literature.task.scanner.fixed-delay:60000}"
    )
    public void scan() {
        if (!properties.isEnabled()) {
            return;
        }
        LiteratureSearchTaskService.ScanResult result = taskService.scanStalledTasks(
                properties.getPendingAge(),
                properties.getRunningTimeout(),
                properties.getBatchSize()
        );
        if (result.hasWork()) {
            log.info("文献检索任务 scanner 完成 requeuedPending={} timedOutRunning={}",
                    result.requeuedPendingCount(),
                    result.timedOutRunningCount());
        }
    }
}
