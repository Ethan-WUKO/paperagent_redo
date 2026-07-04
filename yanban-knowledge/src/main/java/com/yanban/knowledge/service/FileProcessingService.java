package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileProcessingService {

    private static final int CHUNK_SIZE = 500;

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final MinioClient minioClient;
    private final KnowledgeStorageProperties storageProperties;
    private final VectorizationService vectorizationService;
    private final OcrProvider ocrProvider;
    private final Tika tika = new Tika();

    public FileProcessingService(KbDocumentRepository documents,
                                 KbChunkRepository chunks,
                                 MinioClient minioClient,
                                 KnowledgeStorageProperties storageProperties,
                                 VectorizationService vectorizationService,
                                 ObjectProvider<OcrProvider> ocrProvider) {
        this.documents = documents;
        this.chunks = chunks;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
        this.vectorizationService = vectorizationService;
        this.ocrProvider = ocrProvider.getIfAvailable();
    }

    @Transactional
    public void process(FileProcessingMessage message) {
        KbDocument document = documents.findById(message.documentId())
                .orElseThrow(() -> new IllegalStateException("知识库文档不存在: " + message.documentId()));
        document.setStatus("PROCESSING");
        document.setErrorMessage(null);
        documents.save(document);

        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(storageProperties.getBucket())
                .object(message.objectKey())
                .build())) {
            byte[] bytes = in.readAllBytes();
            String text = extractText(document, bytes);
            chunks.deleteByDocumentId(document.getId());
            List<KbChunk> persistedChunks = chunks.saveAll(splitText(document.getId(), text));
            vectorizationService.vectorizeDocument(document, persistedChunks);
            document.setStatus("READY");
            document.setErrorMessage(null);
        } catch (Exception ex) {
            document.setStatus("FAILED");
            document.setErrorMessage(limitError(ex.getMessage()));
        }

        documents.save(document);
    }

    private String extractText(KbDocument document, byte[] bytes) throws Exception {
        String mimeType = document.getMimeType();
        if (mimeType != null && mimeType.toLowerCase().startsWith("image/")) {
            if (ocrProvider == null) {
                throw new IllegalStateException("OCR 未配置");
            }
            return ocrProvider.extractText(bytes, mimeType, document.getFilename());
        }
        return tika.parseToString(new java.io.ByteArrayInputStream(bytes));
    }

    List<KbChunk> splitText(Long documentId, String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        List<KbChunk> result = new ArrayList<>();
        if (normalized.isEmpty()) {
            result.add(new KbChunk(documentId, 0, ""));
            return result;
        }
        int index = 0;
        for (int start = 0; start < normalized.length(); start += CHUNK_SIZE) {
            int end = Math.min(normalized.length(), start + CHUNK_SIZE);
            result.add(new KbChunk(documentId, index++, normalized.substring(start, end)));
        }
        return result;
    }

    private String limitError(String message) {
        if (message == null || message.isBlank()) {
            return "文件解析失败";
        }
        return message.length() > 255 ? message.substring(0, 255) : message;
    }
}
