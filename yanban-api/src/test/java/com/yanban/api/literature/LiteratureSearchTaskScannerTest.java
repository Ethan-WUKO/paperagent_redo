package com.yanban.api.literature;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.paper.literature.LiteratureSearchTaskService;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LiteratureSearchTaskScannerTest {

    @Test
    void scanSkipsWhenDisabled() {
        LiteratureSearchTaskService taskService = mock(LiteratureSearchTaskService.class);
        LiteratureSearchTaskScannerProperties properties = new LiteratureSearchTaskScannerProperties();
        properties.setEnabled(false);
        LiteratureSearchTaskScanner scanner = new LiteratureSearchTaskScanner(taskService, properties);

        scanner.scan();

        verifyNoInteractions(taskService);
    }

    @Test
    void scanUsesConfiguredThresholdsWhenEnabled() {
        LiteratureSearchTaskService taskService = mock(LiteratureSearchTaskService.class);
        LiteratureSearchTaskScannerProperties properties = new LiteratureSearchTaskScannerProperties();
        properties.setPendingAge(Duration.ofMinutes(3));
        properties.setRunningTimeout(Duration.ofMinutes(20));
        properties.setBatchSize(25);
        LiteratureSearchTaskScanner scanner = new LiteratureSearchTaskScanner(taskService, properties);
        when(taskService.scanStalledTasks(Duration.ofMinutes(3), Duration.ofMinutes(20), 25))
                .thenReturn(new LiteratureSearchTaskService.ScanResult(1, 0));

        scanner.scan();

        verify(taskService).scanStalledTasks(Duration.ofMinutes(3), Duration.ofMinutes(20), 25);
    }

    @Test
    void scanDoesNotRequireWorkToBeFound() {
        LiteratureSearchTaskService taskService = mock(LiteratureSearchTaskService.class);
        LiteratureSearchTaskScannerProperties properties = new LiteratureSearchTaskScannerProperties();
        LiteratureSearchTaskScanner scanner = new LiteratureSearchTaskScanner(taskService, properties);
        when(taskService.scanStalledTasks(properties.getPendingAge(), properties.getRunningTimeout(), properties.getBatchSize()))
                .thenReturn(new LiteratureSearchTaskService.ScanResult(0, 0));

        scanner.scan();

        verify(taskService).scanStalledTasks(properties.getPendingAge(), properties.getRunningTimeout(), properties.getBatchSize());
    }
}
