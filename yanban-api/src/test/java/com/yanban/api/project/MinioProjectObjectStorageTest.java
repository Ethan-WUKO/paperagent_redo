package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.service.KnowledgeBucketProvisioner;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class MinioProjectObjectStorageTest {

    @Test
    void uploadFailureLogsOnlySanitizedDiagnosticWithoutThrowable() throws Exception {
        KnowledgeBucketProvisioner provisioner = mock(KnowledgeBucketProvisioner.class);
        org.mockito.Mockito.doThrow(new IllegalStateException(
                "accessKey=AKIA-SECRET password:super-secret token=jwt-secret Authorization: Bearer bearer-secret"))
                .when(provisioner).ensureBucketExists();
        KnowledgeStorageProperties storageProperties = new KnowledgeStorageProperties();
        storageProperties.setBucket("yanban-agent");
        ProjectStorageProperties projectProperties = new ProjectStorageProperties();
        projectProperties.setMinioObjectPrefix("projects");
        MinioProjectObjectStorage storage = new MinioProjectObjectStorage(
                mock(MinioClient.class), provisioner, storageProperties, projectProperties, new ObjectMapper());

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(MinioProjectObjectStorage.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> storage.storeFile(
                    "projects/2/48957c77-d951-4568-b500-44886ea708cf", "paper.tex",
                    new MockMultipartFile("file", "paper.tex", "text/plain", "content".getBytes())))
                    .isInstanceOf(ResponseStatusException.class);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("accessKey=<redacted>", "password=<redacted>", "token=<redacted>",
                            "Authorization=<redacted>")
                    .doesNotContain("AKIA-SECRET", "super-secret", "jwt-secret", "bearer-secret");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
