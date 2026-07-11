package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import com.yanban.knowledge.service.EmbeddingClient;
import com.yanban.knowledge.service.FileProcessingConsumer;
import com.yanban.knowledge.service.FileProcessingMessage;
import com.yanban.knowledge.service.KnowledgeIndexService;
import com.yanban.knowledge.service.OcrProvider;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.util.Map;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_file_processing_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class FileProcessingConsumerIntegrationTest {

    @Autowired
    FileProcessingConsumer consumer;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    KbDocumentRepository documents;

    @Autowired
    KbChunkRepository chunks;

    @Autowired
    SysUserRepository users;

    @MockBean
    MinioClient minioClient;

    @MockBean
    EmbeddingClient embeddingClient;

    @MockBean
    KnowledgeIndexService knowledgeIndexService;

    @MockBean
    OcrProvider ocrProvider;

    @Test
    void processReadyDocumentAndPersistChunks() throws Exception {
        Long userId = users.save(new SysUser("file_processor_ready", "noop")).getId();
        KbDocument document = new KbDocument(userId, "sample.txt", "PROCESSING", false);
        document.setObjectKey("kb/documents/7001/sample.txt");
        documents.save(document);

        when(minioClient.getObject(any())).thenReturn(new GetObjectResponse(
                Headers.of(Map.of("Content-Type", "text/plain")),
                "yanban-agent",
                "us-east-1",
                document.getObjectKey(),
                new ByteArrayInputStream("第一段\n第二段\n第三段".getBytes())
        ));
        when(embeddingClient.embed(any())).thenReturn(vector1024());
        when(knowledgeIndexService.indexChunk(any())).thenReturn("es-doc-1");

        consumer.onMessage(objectMapper.writeValueAsString(new FileProcessingMessage(
                document.getId(),
                document.getUserId(),
                document.getObjectKey()
        )));

        KbDocument updated = documents.findById(document.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("READY");
        assertThat(updated.getErrorMessage()).isNull();
        assertThat(chunks.findByDocumentIdOrderByChunkIndexAsc(document.getId())).isNotEmpty();
    }

    @Test
    void markDocumentFailedWhenMinioReadFails() throws Exception {
        Long userId = users.save(new SysUser("file_processor_failed", "noop")).getId();
        KbDocument document = new KbDocument(userId, "broken.pdf", "PROCESSING", false);
        document.setObjectKey("kb/documents/7002/broken.pdf");
        documents.save(document);

        when(minioClient.getObject(any())).thenThrow(new RuntimeException("broken object stream"));

        consumer.onMessage(objectMapper.writeValueAsString(new FileProcessingMessage(
                document.getId(),
                document.getUserId(),
                document.getObjectKey()
        )));

        KbDocument updated = documents.findById(document.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("FAILED");
        assertThat(updated.getErrorMessage()).isNotBlank();
    }

    @Test
    void imageDocumentUsesOcrProvider() throws Exception {
        Long userId = users.save(new SysUser("file_processor_image", "noop")).getId();
        KbDocument document = new KbDocument(userId, "scan.png", "PROCESSING", false);
        document.setObjectKey("kb/documents/7003/scan.png");
        document.setMimeType("image/png");
        documents.save(document);

        when(minioClient.getObject(any())).thenReturn(new GetObjectResponse(
                Headers.of(Map.of("Content-Type", "image/png")),
                "yanban-agent",
                "us-east-1",
                document.getObjectKey(),
                new ByteArrayInputStream("pngbytes".getBytes())
        ));
        when(ocrProvider.extractText(any(), any(), any())).thenReturn("OCR TEXT");
        when(embeddingClient.embed(any())).thenReturn(vector1024());
        when(knowledgeIndexService.indexChunk(any())).thenReturn("es-doc-1");

        consumer.onMessage(objectMapper.writeValueAsString(new FileProcessingMessage(document.getId(), document.getUserId(), document.getObjectKey())));

        KbDocument updated = documents.findById(document.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("READY");
        assertThat(chunks.findByDocumentIdOrderByChunkIndexAsc(document.getId()).get(0).getChunkText()).contains("OCR TEXT");
    }

    private java.util.List<Double> vector1024() {
        return java.util.Collections.nCopies(1024, 0.01d);
    }
}
