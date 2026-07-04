package com.yanban.knowledge.service;

import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeIngestionService {

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final FileProcessingService fileProcessingService;
    private final VectorizationService vectorizationService;
    private final Tika tika = new Tika();

    public KnowledgeIngestionService(KbDocumentRepository documents,
                                     KbChunkRepository chunks,
                                     FileProcessingService fileProcessingService,
                                     VectorizationService vectorizationService) {
        this.documents = documents;
        this.chunks = chunks;
        this.fileProcessingService = fileProcessingService;
        this.vectorizationService = vectorizationService;
    }

    @Transactional
    public KbDocument ingestSimple(Long userId, MultipartFile file, boolean isPublic) {
        try {
            KbDocument document = documents.save(new KbDocument(
                    userId,
                    file.getOriginalFilename() == null ? "uploaded-file" : file.getOriginalFilename(),
                    "PROCESSING",
                    isPublic
            ));
            String text;
            try (InputStream in = file.getInputStream()) {
                text = tika.parseToString(in);
            }
            List<KbChunk> createdChunks = fileProcessingService.splitText(document.getId(), text);
            chunks.saveAll(createdChunks);
            document.setStatus("READY");
            return documents.save(document);
        } catch (Exception ex) {
            throw new IllegalStateException("文件解析失败", ex);
        }
    }

    @Transactional
    public KbDocument ingestText(Long userId,
                                 String filename,
                                 String text,
                                 boolean isPublic,
                                 String sourceType,
                                 String mimeType) {
        try {
            KbDocument document = documents.save(new KbDocument(
                    userId,
                    filename == null || filename.isBlank() ? "demo-document.md" : filename,
                    "PROCESSING",
                    isPublic
            ));
            document.setSourceType(sourceType);
            document.setMimeType(mimeType == null || mimeType.isBlank() ? "text/plain" : mimeType);
            document.setFileSize((long) (text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length));
            List<KbChunk> persistedChunks = chunks.saveAll(fileProcessingService.splitText(document.getId(), text));
            vectorizationService.vectorizeDocument(document, persistedChunks);
            document.setStatus("READY");
            document.setErrorMessage(null);
            return documents.save(document);
        } catch (Exception ex) {
            throw new IllegalStateException("文本知识库入库失败", ex);
        }
    }
}
