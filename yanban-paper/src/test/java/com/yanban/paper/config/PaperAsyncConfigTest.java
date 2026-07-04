package com.yanban.paper.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class PaperAsyncConfigTest {

    private final PaperAsyncConfig config = new PaperAsyncConfig();

    @Test
    void paperTaskExecutorMatchesRoadmapBaseline() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.paperTaskExecutor();

        assertThat(executor.getThreadNamePrefix()).isEqualTo("yanban-paper-");
        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
        assertThat(executor.getQueueCapacity()).isEqualTo(20);
    }

    @Test
    void literatureTaskExecutorKeepsCurrentConcurrencyBaseline() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.literatureTaskExecutor();

        assertThat(executor.getThreadNamePrefix()).isEqualTo("yanban-literature-");
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(6);
        assertThat(executor.getQueueCapacity()).isEqualTo(50);
    }
}
