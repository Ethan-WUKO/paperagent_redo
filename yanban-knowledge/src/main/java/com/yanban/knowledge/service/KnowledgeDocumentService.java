package com.yanban.knowledge.service;

import com.yanban.core.user.UserAccountPolicy;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.web.KbDocumentListItemResponse;
import com.yanban.knowledge.web.KbDocumentPreviewResponse;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeDocumentService {

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KnowledgeIndexService indexService;
    private final MinioClient minioClient;
    private final KnowledgeStorageProperties storageProperties;
    private final ObjectProvider<UserAccountPolicy> accountPolicy;

    public KnowledgeDocumentService(KbDocumentRepository documents,
                                    KbChunkRepository chunks,
                                    KnowledgeIndexService indexService,
                                    MinioClient minioClient,
                                    KnowledgeStorageProperties storageProperties,
                                    ObjectProvider<UserAccountPolicy> accountPolicy) {
        this.documents = documents;
        this.chunks = chunks;
        this.indexService = indexService;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
        this.accountPolicy = accountPolicy;
    }

    @Transactional(readOnly = true)
    public List<KbDocumentListItemResponse> listOwnedDocuments(Long userId) {
        return documents.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(KbDocumentListItemResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public KbDocumentPreviewResponse previewOwnedDocument(Long userId, Long documentId, Integer requestedMaxChars) {
        KbDocument document = documents.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库文档不存在"));
        int maxChars = normalizeMaxChars(requestedMaxChars);
        int totalChunks = chunks.countByDocumentId(documentId);
        List<com.yanban.knowledge.domain.KbChunk> previewChunks = chunks.findByDocumentIdOrderByChunkIndexAsc(
                documentId,
                PageRequest.of(0, 24)
        );
        StringBuilder content = new StringBuilder(Math.min(maxChars, 16_384));
        int usedChunks = 0;
        boolean truncated = totalChunks > previewChunks.size();
        for (com.yanban.knowledge.domain.KbChunk chunk : previewChunks) {
            if (content.length() >= maxChars) {
                truncated = true;
                break;
            }
            if (usedChunks > 0 && content.length() + 2 <= maxChars) {
                content.append("\n\n");
            }
            String text = chunk.getChunkText() == null ? "" : chunk.getChunkText();
            int remaining = maxChars - content.length();
            if (text.length() > remaining) {
                content.append(text, 0, Math.max(0, remaining));
                truncated = true;
                usedChunks++;
                break;
            }
            content.append(text);
            usedChunks++;
        }
        return KbDocumentPreviewResponse.of(document, totalChunks, usedChunks, maxChars, truncated, content.toString());
    }

    @Transactional
    public void deleteOwnedDocument(Long userId, Long documentId) {
        KbDocument document = documents.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库文档不存在"));
        UserAccountPolicy policy = accountPolicy.getIfAvailable();
        if (policy != null) {
            policy.assertCanDeleteKnowledgeDocument(userId, document.getSourceType());
        }
        chunks.deleteByDocumentId(documentId);
        indexService.deleteByDocumentId(documentId);
        removeObjectQuietly(document.getObjectKey());
        documents.delete(document);
    }

    private void removeObjectQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(storageProperties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("删除 MinIO 文档失败", ex);
        }
    }

    private int normalizeMaxChars(Integer requestedMaxChars) {
        if (requestedMaxChars == null) {
            return 12_000;
        }
        return Math.max(1_000, Math.min(50_000, requestedMaxChars));
    }
}
