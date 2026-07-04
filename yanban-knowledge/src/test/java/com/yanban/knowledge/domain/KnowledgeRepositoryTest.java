package com.yanban.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = KnowledgeRepositoryTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class KnowledgeRepositoryTest {

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KbChunkUploadRepository uploads;

    @Autowired
    KnowledgeRepositoryTest(KbDocumentRepository documents,
                            KbChunkRepository chunks,
                            KbChunkUploadRepository uploads) {
        this.documents = documents;
        this.chunks = chunks;
        this.uploads = uploads;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {KbDocument.class, KbChunk.class, KbChunkUpload.class})
    @EnableJpaRepositories(basePackageClasses = {KbDocumentRepository.class, KbChunkRepository.class, KbChunkUploadRepository.class})
    static class TestConfig {
    }

    @Test
    void insertKbDocumentChunkAndChunkUpload() {
        KbDocument document = documents.save(new KbDocument(3001L, "notes.pdf", "PROCESSING", false));
        document.setObjectKey("kb/notes.pdf");
        document.setMimeType("application/pdf");
        document.setFileSize(1024L);
        document.setErrorMessage(null);
        documents.save(document);

        chunks.save(new KbChunk(document.getId(), 0, "第一段内容"));
        uploads.save(new KbChunkUpload(3001L, "upload-1", "notes.pdf", 1, 2, 512L, "md5", "tmp/upload-1/1", "UPLOADED"));

        assertThat(documents.findByIdAndUserId(document.getId(), 3001L)).isPresent();
        assertThat(chunks.findByDocumentIdOrderByChunkIndexAsc(document.getId())).hasSize(1);
        assertThat(uploads.findByUploadIdOrderByChunkNumberAsc("upload-1")).hasSize(1);
    }
}
