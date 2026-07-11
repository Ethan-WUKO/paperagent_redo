package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBucketProvisioner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBucketProvisioner.class);

    private final MinioClient minioClient;
    private final KnowledgeStorageProperties storageProperties;
    private final AtomicBoolean ensured = new AtomicBoolean(false);

    public KnowledgeBucketProvisioner(MinioClient minioClient,
                                      KnowledgeStorageProperties storageProperties) {
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
    }

    public void ensureBucketExists() throws Exception {
        if (ensured.get()) {
            return;
        }
        synchronized (this) {
            if (ensured.get()) {
                return;
            }
            String bucket = storageProperties.getBucket();
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucket)
                    .build());
            if (!exists) {
                try {
                    minioClient.makeBucket(MakeBucketArgs.builder()
                            .bucket(bucket)
                            .build());
                    log.info("已自动创建知识库存储 bucket: {}", bucket);
                } catch (ErrorResponseException ex) {
                    String code = ex.errorResponse() == null ? null : ex.errorResponse().code();
                    if (!"BucketAlreadyOwnedByYou".equals(code) && !"BucketAlreadyExists".equals(code)) {
                        throw ex;
                    }
                }
            }
            ensured.set(true);
        }
    }
}
