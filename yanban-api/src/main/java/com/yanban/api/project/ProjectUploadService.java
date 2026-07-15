package com.yanban.api.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Imports a browser-selected folder into an isolated MinIO Project snapshot. */
@Service
public class ProjectUploadService {

    private static final int TEXT_SAMPLE_BYTES = 8 * 1024;

    private final ProjectRepository projects;
    private final ProjectObjectStorage objectStorage;
    private final ProjectStorageProperties properties;
    private final ObjectMapper objectMapper;

    public ProjectUploadService(ProjectRepository projects,
                                ProjectObjectStorage objectStorage,
                                ProjectStorageProperties properties,
                                ObjectMapper objectMapper) {
        this.projects = projects;
        this.objectStorage = objectStorage;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Project upload(Long userId, String name, List<String> includeRules,
                          List<String> ignoreRules, List<MultipartFile> files) {
        validateRequest(userId, name, includeRules, ignoreRules, files);
        String serializedIncludes = serialize(includeRules);
        String serializedIgnores = serialize(ignoreRules == null ? List.of() : ignoreRules);
        String prefix = objectStorage.createPrefix(userId);
        try {
            Project policyProject = Project.minioUpload(userId, name.trim(), prefix,
                    serializedIncludes, serializedIgnores);
            List<ProjectObjectEntry> entries = storeAllowedFiles(files, prefix,
                    ProjectPathPolicy.from(policyProject, objectMapper));
            objectStorage.writeManifest(prefix, entries);
            Project project = Project.minioUpload(userId, name.trim(), prefix,
                    serializedIncludes, serializedIgnores);
            return projects.saveAndFlush(project);
        } catch (RuntimeException ex) {
            safeDeletePrefix(prefix);
            throw ex;
        }
    }

    private void validateRequest(Long userId, String name, List<String> includeRules,
                                 List<String> ignoreRules, List<MultipartFile> files) {
        if (userId == null || userId <= 0 || name == null || name.isBlank() || name.length() > 255) {
            throw new InvalidProjectPathException("Project upload request is invalid");
        }
        if (files == null || files.isEmpty()) {
            throw new InvalidProjectPathException("Project folder must contain at least one file");
        }
        ProjectPathPolicy.validateRules(includeRules, "includeRules", false);
        ProjectPathPolicy.validateRules(ignoreRules == null ? List.of() : ignoreRules, "ignoreRules", true);
    }

    private List<ProjectObjectEntry> storeAllowedFiles(List<MultipartFile> files, String prefix,
                                                       ProjectPathPolicy policy) {
        Set<Path> destinations = new HashSet<>();
        List<ProjectObjectEntry> entries = new ArrayList<>();
        String commonFolder = commonTopLevelFolder(files);
        long admittedBytes = 0;

        for (MultipartFile file : files) {
            Path relative = uploadedRelativePath(file, commonFolder);
            if (relative.getNameCount() > properties.getMaxTraversalDepth() || !policy.allows(relative)) {
                continue;
            }
            if (!destinations.add(relative)) {
                throw new InvalidProjectPathException("Project upload contains duplicate paths");
            }
            long size = file.getSize();
            if (size < 0 || size > properties.getMaxFileBytes()
                    || size > properties.getMaxTotalBytes() - admittedBytes) {
                throw new ProjectTraversalLimitException("Project byte budget exceeded");
            }
            if (!isReadableTextFile(file)) {
                continue;
            }
            if (entries.size() >= properties.getMaxFiles()) {
                throw new ProjectTraversalLimitException("Project file-count budget exceeded");
            }
            admittedBytes += size;
            entries.add(objectStorage.storeFile(prefix, portable(relative), file));
        }
        if (entries.isEmpty()) {
            throw new InvalidProjectPathException("Project folder contains no readable files allowed by its rules");
        }
        entries.sort(java.util.Comparator.comparing(ProjectObjectEntry::path));
        return List.copyOf(entries);
    }

    private boolean isReadableTextFile(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            byte[] sample = input.readNBytes((int) Math.min(TEXT_SAMPLE_BYTES, file.getSize()));
            for (byte value : sample) {
                if (value == 0) return false;
            }
            return true;
        } catch (IOException ex) {
            throw new InvalidProjectPathException("Project upload file is unreadable", ex);
        }
    }

    private String commonTopLevelFolder(List<MultipartFile> files) {
        String common = null;
        for (MultipartFile file : files) {
            Path path = ProjectPathGuard.parseRelative(normalizeUploadName(file), "uploaded file path");
            if (path.getNameCount() < 2) return null;
            String first = path.getName(0).toString();
            if (common == null) common = first;
            else if (!common.equals(first)) return null;
        }
        return common;
    }

    private Path uploadedRelativePath(MultipartFile file, String commonFolder) {
        Path path = ProjectPathGuard.parseRelative(normalizeUploadName(file), "uploaded file path");
        if (commonFolder != null && path.getNameCount() > 1
                && commonFolder.equals(path.getName(0).toString())) {
            path = path.subpath(1, path.getNameCount());
        }
        return ProjectPathGuard.parseRelative(path.toString(), "uploaded file path");
    }

    private String normalizeUploadName(MultipartFile file) {
        String original = file == null ? null : file.getOriginalFilename();
        return original == null ? null : original.replace('\\', '/');
    }

    private String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String serialize(List<String> rules) {
        try {
            return objectMapper.writeValueAsString(rules);
        } catch (JsonProcessingException ex) {
            throw new InvalidProjectPathException("Project rules are invalid", ex);
        }
    }

    private void safeDeletePrefix(String prefix) {
        try {
            objectStorage.deletePrefix(prefix);
        } catch (RuntimeException ignored) {
            // Best effort for an unreferenced, incomplete upload.
        }
    }
}
