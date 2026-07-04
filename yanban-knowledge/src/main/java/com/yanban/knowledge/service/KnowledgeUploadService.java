package com.yanban.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.user.UserAccountPolicy;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.config.KnowledgeUploadProperties;
import com.yanban.knowledge.domain.KbChunkUpload;
import com.yanban.knowledge.domain.KbChunkUploadRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.web.ChunkUploadRequest;
import com.yanban.knowledge.web.ChunkUploadResponse;
import com.yanban.knowledge.web.KbDocumentResponse;
import com.yanban.knowledge.web.MergeUploadRequest;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Service
public class KnowledgeUploadService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUploadService.class);

    private final KbChunkUploadRepository chunkUploads;
    private final KbDocumentRepository documents;
    private final MinioClient minioClient;
    private final KnowledgeStorageProperties storageProperties;
    private final KnowledgeUploadProperties uploadProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<UserAccountPolicy> accountPolicy;

    public KnowledgeUploadService(KbChunkUploadRepository chunkUploads,
                                  KbDocumentRepository documents,
                                  MinioClient minioClient,
                                  KnowledgeStorageProperties storageProperties,
                                  KnowledgeUploadProperties uploadProperties,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper,
                                  ObjectProvider<UserAccountPolicy> accountPolicy) {
        this.chunkUploads = chunkUploads;
        this.documents = documents;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
        this.uploadProperties = uploadProperties;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.accountPolicy = accountPolicy;
    }

    @Transactional
    public ChunkUploadResponse uploadChunk(Long userId, ChunkUploadRequest request) {
        validateChunkRequest(request);
        try {
            byte[] bytes = request.file().getBytes();
            UserAccountPolicy policy = accountPolicy.getIfAvailable();
            if (policy != null) {
                policy.assertCanUploadKnowledge(userId, bytes.length);
            }
            validateMd5(request.chunkMd5(), bytes);

            String tempObjectKey = buildTempObjectKey(userId, request.uploadId(), request.chunkNumber());
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(storageProperties.getBucket())
                    .object(tempObjectKey)
                    .stream(new java.io.ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(resolveContentType(request.file().getContentType()))
                    .build());

            chunkUploads.findByUploadIdAndChunkNumber(request.uploadId(), request.chunkNumber())
                    .ifPresent(chunkUploads::delete);
            chunkUploads.save(new KbChunkUpload(
                    userId,
                    request.uploadId(),
                    request.filename(),
                    request.chunkNumber(),
                    request.totalChunks(),
                    (long) bytes.length,
                    normalizeMd5(request.chunkMd5()),
                    tempObjectKey,
                    "UPLOADED"
            ));
            return new ChunkUploadResponse(
                    request.uploadId(),
                    request.chunkNumber(),
                    request.totalChunks(),
                    "UPLOADED",
                    tempObjectKey
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("知识库上传分片失败: userId={}, uploadId={}, filename={}, chunkNumber={}, totalChunks={}",
                    userId, request.uploadId(), request.filename(), request.chunkNumber(), request.totalChunks(), ex);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "上传分片失败", ex);
        }
    }

    @Transactional
    public KbDocumentResponse mergeChunks(Long userId, MergeUploadRequest request) {
        List<KbChunkUpload> uploadedChunks = chunkUploads.findByUploadIdOrderByChunkNumberAsc(request.uploadId());
        if (uploadedChunks.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "未找到上传分片");
        }
        if (!uploadedChunks.stream().allMatch(chunk -> chunk.getUserId().equals(userId))) {
            throw new ResponseStatusException(BAD_REQUEST, "上传分片不属于当前用户");
        }
        if (uploadedChunks.size() != request.totalChunks()) {
            throw new ResponseStatusException(BAD_REQUEST, "分片数量不完整");
        }
        validateChunkSequence(uploadedChunks, request.totalChunks());
        UserAccountPolicy policy = accountPolicy.getIfAvailable();
        if (policy != null) {
            long totalBytes = uploadedChunks.stream().mapToLong(KbChunkUpload::getChunkSize).sum();
            policy.assertCanUploadKnowledge(userId, totalBytes);
        }

        Path mergedFile = null;
        try {
            mergedFile = Files.createTempFile("yanban-kb-merge-", ".bin");
            try (OutputStream out = Files.newOutputStream(mergedFile)) {
                for (KbChunkUpload upload : uploadedChunks) {
                    try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                            .bucket(storageProperties.getBucket())
                            .object(upload.getTempObjectKey())
                            .build())) {
                        in.transferTo(out);
                    }
                }
            }

            String objectKey = buildDocumentObjectKey(userId, request.filename());
            long fileSize = Files.size(mergedFile);
            try (InputStream mergedInputStream = Files.newInputStream(mergedFile)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(storageProperties.getBucket())
                        .object(objectKey)
                        .stream(mergedInputStream, fileSize, -1)
                        .contentType(resolveContentType(request.mimeType()))
                        .build());
            }

            KbDocument document = new KbDocument(userId, request.filename(), "PROCESSING", request.isPublic());
            document.setObjectKey(objectKey);
            document.setMimeType(resolveContentType(request.mimeType()));
            document.setFileSize(fileSize);
            document = documents.save(document);

            kafkaTemplate.send(uploadProperties.getProcessingTopic(), toMessage(document));

            for (KbChunkUpload upload : uploadedChunks) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(storageProperties.getBucket())
                        .object(upload.getTempObjectKey())
                        .build());
            }
            chunkUploads.deleteAll(uploadedChunks);
            return KbDocumentResponse.from(document);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("知识库合并分片失败: userId={}, uploadId={}, filename={}, totalChunks={}",
                    userId, request.uploadId(), request.filename(), request.totalChunks(), ex);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "合并分片失败", ex);
        } finally {
            if (mergedFile != null) {
                try {
                    Files.deleteIfExists(mergedFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void validateChunkRequest(ChunkUploadRequest request) {
        if (request.file() == null || request.file().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "分片文件不能为空");
        }
        if (request.chunkNumber() == null || request.totalChunks() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "分片参数不完整");
        }
        if (request.chunkNumber() >= request.totalChunks()) {
            throw new ResponseStatusException(BAD_REQUEST, "chunkNumber 必须小于 totalChunks");
        }
    }

    private void validateChunkSequence(List<KbChunkUpload> uploads, int totalChunks) {
        int start = uploads.get(0).getChunkNumber() == 0 ? 0 : 1;
        for (int i = 0; i < uploads.size(); i++) {
            int expected = start + i;
            if (!uploads.get(i).getChunkNumber().equals(expected)) {
                throw new ResponseStatusException(BAD_REQUEST, "分片序号不连续");
            }
            if (!uploads.get(i).getTotalChunks().equals(totalChunks)) {
                throw new ResponseStatusException(BAD_REQUEST, "totalChunks 不一致");
            }
        }
    }

    private void validateMd5(String expectedMd5, byte[] bytes) {
        String normalized = normalizeMd5(expectedMd5);
        if (normalized == null) {
            return;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            String actual = HexFormat.of().formatHex(digest.digest(bytes));
            if (!normalized.equals(actual)) {
                throw new ResponseStatusException(BAD_REQUEST, "分片 MD5 校验失败");
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "分片 MD5 校验失败", ex);
        }
    }

    private String normalizeMd5(String md5) {
        if (md5 == null || md5.isBlank()) {
            return null;
        }
        return md5.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveContentType(String contentType) {
        return (contentType == null || contentType.isBlank())
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : contentType;
    }

    private String buildTempObjectKey(Long userId, String uploadId, Integer chunkNumber) {
        return uploadProperties.getTempPrefix() + "/" + userId + "/" + uploadId + "/chunk-" + chunkNumber;
    }

    private String buildDocumentObjectKey(Long userId, String filename) {
        String safeFilename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return uploadProperties.getObjectPrefix() + "/" + userId + "/" + UUID.randomUUID() + "-" + safeFilename;
    }

    private String toMessage(KbDocument document) {
        try {
            return objectMapper.writeValueAsString(new FileProcessingMessage(
                    document.getId(),
                    document.getUserId(),
                    document.getObjectKey()
            ));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "构造处理消息失败", ex);
        }
    }
}
