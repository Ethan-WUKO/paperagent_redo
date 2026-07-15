package com.yanban.api.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.service.KnowledgeBucketProvisioner;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MinioProjectObjectStorage implements ProjectObjectStorage {

    private static final long MAX_MANIFEST_BYTES = 5L * 1024 * 1024;

    private final MinioClient minioClient;
    private final KnowledgeBucketProvisioner bucketProvisioner;
    private final KnowledgeStorageProperties minioProperties;
    private final ProjectStorageProperties projectProperties;
    private final ObjectMapper objectMapper;

    public MinioProjectObjectStorage(MinioClient minioClient,
                                     KnowledgeBucketProvisioner bucketProvisioner,
                                     KnowledgeStorageProperties minioProperties,
                                     ProjectStorageProperties projectProperties,
                                     ObjectMapper objectMapper) {
        this.minioClient = minioClient;
        this.bucketProvisioner = bucketProvisioner;
        this.minioProperties = minioProperties;
        this.projectProperties = projectProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String createPrefix(Long userId) {
        if (userId == null || userId <= 0) throw new InvalidProjectPathException("Project owner is invalid");
        return normalizedBasePrefix() + "/" + userId + "/" + UUID.randomUUID();
    }

    @Override
    public ProjectObjectEntry storeFile(String prefix, String relativePath, MultipartFile file) {
        String safePrefix = validatePrefix(prefix);
        String portablePath = portableRelativePath(relativePath);
        try {
            bucketProvisioner.ensureBucketExists();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream raw = file.getInputStream(); DigestInputStream input = new DigestInputStream(raw, digest)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket())
                        .object(fileKey(safePrefix, portablePath))
                        .stream(input, file.getSize(), -1)
                        .contentType(contentType(file.getContentType()))
                        .build());
            }
            return new ProjectObjectEntry(portablePath, file.getSize(), Instant.now(),
                    HexFormat.of().formatHex(digest.digest()));
        } catch (Exception ex) {
            throw unavailable("Project file upload failed", ex);
        }
    }

    @Override
    public void writeManifest(String prefix, List<ProjectObjectEntry> files) {
        String safePrefix = validatePrefix(prefix);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(new ProjectObjectManifest(files));
            if (bytes.length > MAX_MANIFEST_BYTES) {
                throw new ProjectTraversalLimitException("Project manifest budget exceeded");
            }
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket())
                    .object(manifestKey(safePrefix))
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("application/json")
                    .build());
        } catch (ProjectTraversalLimitException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable("Project manifest upload failed", ex);
        }
    }

    @Override
    public List<ProjectObjectEntry> readManifest(String prefix) {
        String safePrefix = validatePrefix(prefix);
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket()).object(manifestKey(safePrefix)).build())) {
            byte[] bytes = readBounded(input, MAX_MANIFEST_BYTES);
            ProjectObjectManifest manifest = objectMapper.readValue(bytes, ProjectObjectManifest.class);
            if (manifest == null || !ProjectObjectManifest.SCHEMA.equals(manifest.schema()) || manifest.files() == null) {
                throw new InvalidProjectPathException("Project object manifest is invalid");
            }
            List<ProjectObjectEntry> entries = new ArrayList<>();
            for (ProjectObjectEntry entry : manifest.files()) {
                if (entry == null || entry.sizeBytes() < 0 || entry.modifiedAt() == null
                        || entry.sha256() == null || !entry.sha256().matches("[0-9a-f]{64}")) {
                    throw new InvalidProjectPathException("Project object manifest is invalid");
                }
                String path = portableRelativePath(entry.path());
                entries.add(new ProjectObjectEntry(path, entry.sizeBytes(), entry.modifiedAt(), entry.sha256()));
            }
            entries.sort(Comparator.comparing(ProjectObjectEntry::path));
            return List.copyOf(entries);
        } catch (InvalidProjectPathException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable("Project manifest is unavailable", ex);
        }
    }

    @Override
    public byte[] readFile(String prefix, String relativePath, long maxBytes) {
        String safePrefix = validatePrefix(prefix);
        String portablePath = portableRelativePath(relativePath);
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket()).object(fileKey(safePrefix, portablePath)).build())) {
            return readBounded(input, maxBytes);
        } catch (ProjectTraversalLimitException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable("Project file is unavailable", ex);
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        String safePrefix = validatePrefix(prefix);
        try {
            Iterable<Result<Item>> objects = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket()).prefix(safePrefix + "/").recursive(true).build());
            for (Result<Item> result : objects) {
                String objectName = result.get().objectName();
                if (objectName.startsWith(safePrefix + "/")) {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(bucket()).object(objectName).build());
                }
            }
        } catch (Exception ex) {
            throw unavailable("Project object cleanup failed", ex);
        }
    }

    private byte[] readBounded(InputStream input, long maxBytes) throws Exception {
        if (maxBytes < 0 || maxBytes >= Integer.MAX_VALUE) {
            throw new ProjectTraversalLimitException("Project object read budget exceeded");
        }
        byte[] bytes = input.readNBytes((int) maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new ProjectTraversalLimitException("Project object read budget exceeded");
        }
        return bytes;
    }

    private String portableRelativePath(String relativePath) {
        return ProjectPathGuard.parseRelative(relativePath, "Project object path")
                .toString().replace('\\', '/');
    }

    private String validatePrefix(String prefix) {
        String base = normalizedBasePrefix();
        if (prefix == null || !prefix.matches(java.util.regex.Pattern.quote(base) + "/[1-9][0-9]*/[0-9a-fA-F-]{36}")) {
            throw new InvalidProjectPathException("Project object prefix is invalid");
        }
        return prefix;
    }

    private String normalizedBasePrefix() {
        String value = projectProperties.getMinioObjectPrefix();
        if (value == null) throw new InvalidProjectPathException("Project object prefix is not configured");
        value = value.trim().replace('\\', '/').replaceAll("^/+|/+$", "");
        if (value.isBlank() || value.contains("..") || value.contains("//")) {
            throw new InvalidProjectPathException("Project object prefix is invalid");
        }
        return value;
    }

    private String bucket() {
        return minioProperties.getBucket();
    }

    private String fileKey(String prefix, String path) { return prefix + "/files/" + path; }
    private String manifestKey(String prefix) { return prefix + "/manifest.json"; }
    private String contentType(String value) { return value == null || value.isBlank() ? "application/octet-stream" : value; }

    private ResponseStatusException unavailable(String reason, Exception cause) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, reason, cause);
    }
}
