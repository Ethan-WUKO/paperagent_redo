package com.yanban.api.literature;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.literature.task.scanner")
public class LiteratureSearchTaskScannerProperties {

    private boolean enabled = true;
    private Duration pendingAge = Duration.ofMinutes(2);
    private Duration runningTimeout = Duration.ofMinutes(10);
    private int batchSize = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPendingAge() {
        return pendingAge;
    }

    public void setPendingAge(Duration pendingAge) {
        this.pendingAge = pendingAge;
    }

    public Duration getRunningTimeout() {
        return runningTimeout;
    }

    public void setRunningTimeout(Duration runningTimeout) {
        this.runningTimeout = runningTimeout;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
