package com.yanban.api.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final int MAX_SEARCH_LINE_LENGTH = 2_000;
    private static final int TEXT_SAMPLE_BYTES = 8 * 1024;

    private final ProjectRepository projects;
    private final List<ProjectRootProvider> rootProviders;
    private final ProjectStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final ProjectObjectStorage objectStorage;

    public ProjectService(ProjectRepository projects,
                          List<ProjectRootProvider> rootProviders,
                          ProjectStorageProperties properties,
                          ObjectMapper objectMapper) {
        this(projects, rootProviders, properties, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectService(ProjectRepository projects,
                          List<ProjectRootProvider> rootProviders,
                          ProjectStorageProperties properties,
                          ObjectMapper objectMapper,
                          ProjectObjectStorage objectStorage) {
        this.projects = projects;
        this.rootProviders = List.copyOf(rootProviders);
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.objectStorage = objectStorage;
    }

    @Transactional(readOnly = true)
    public List<ProjectSummaryResponse> list(Long userId) {
        return projects.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(ProjectSummaryResponse::from).toList();
    }

    /** Local bindings stay untouched; server-owned upload snapshots are removed only after the DB commit succeeds. */
    @Transactional
    public void delete(Long userId, Long projectId) {
        Project project = ownedProject(userId, projectId);
        Path managedSnapshot = resolveManagedSnapshotForCleanup(project);
        Runnable objectCleanup = project.getRootType() == ProjectRootType.MINIO_OBJECTS
                ? () -> deleteObjectSnapshot(project) : null;
        projects.delete(project);
        if (managedSnapshot != null) {
            Runnable cleanup = () -> deleteManagedSnapshot(managedSnapshot);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        cleanup.run();
                    }
                });
            } else {
                cleanup.run();
            }
        }
        if (objectCleanup != null) scheduleAfterCommit(objectCleanup);
    }

    private Path resolveManagedSnapshotForCleanup(Project project) {
        if (project.getRootType() != ProjectRootType.MANAGED_UPLOAD) {
            return null;
        }
        try {
            return rootProviderFor(ProjectRootType.MANAGED_UPLOAD).resolve(project).canonicalPath();
        } catch (RuntimeException ex) {
            log.warn("Managed Project snapshot could not be resolved for cleanup projectId={}", project.getId());
            return null;
        }
    }

    private void deleteManagedSnapshot(Path root) {
        try {
            if (!Files.exists(root)) return;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            log.warn("Managed Project snapshot cleanup failed");
        }
    }

    private void deleteObjectSnapshot(Project project) {
        try {
            requireObjectStorage().deletePrefix(project.getRootPath());
        } catch (RuntimeException ex) {
            log.warn("MinIO Project snapshot cleanup failed projectId={}", project.getId());
        }
    }

    private void scheduleAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    @Transactional(readOnly = true)
    public ProjectManifestResponse manifest(Long userId, Long projectId) {
        Project project = ownedProject(userId, projectId);
        ProjectPathPolicy policy = ProjectPathPolicy.from(project, objectMapper);
        List<ProjectFileEntry> files = project.getRootType() == ProjectRootType.MINIO_OBJECTS
                ? listObjectFiles(project, policy) : listFiles(resolveLocal(project, policy));
        String version = sha256(files.stream()
                .map(file -> file.path() + "\u0000" + file.sizeBytes() + "\u0000" + file.modifiedAt() + "\u0000" + file.sha256())
                .reduce("", (left, right) -> left + right + "\n").getBytes(StandardCharsets.UTF_8));
        return new ProjectManifestResponse(projectId, version, files);
    }

    @Transactional(readOnly = true)
    public ProjectFileResponse readFile(Long userId, Long projectId, String relativePath) {
        Project project = ownedProject(userId, projectId);
        ProjectPathPolicy policy = ProjectPathPolicy.from(project, objectMapper);
        if (project.getRootType() == ProjectRootType.MINIO_OBJECTS) {
            return readObjectFile(project, policy, relativePath);
        }
        ResolvedProject resolved = resolveLocal(project, policy);
        final Path realPath;
        try {
            realPath = ProjectPathGuard.resolveExistingFile(resolved.root(), relativePath);
        } catch (ProjectFileUnavailableException ex) {
            throw inaccessibleFile();
        }
        try {
            Path relative = resolved.root().canonicalPath().relativize(realPath);
            BasicFileAttributes attributes = Files.readAttributes(realPath, BasicFileAttributes.class);
            if (!resolved.policy().allows(relative) || !attributes.isRegularFile()
                    || attributes.size() > properties.getMaxFileBytes()
                    || !isReadableTextFile(realPath, attributes.size())) {
                throw inaccessibleFile();
            }
            byte[] content = readBoundedContent(realPath, attributes.size());
            return new ProjectFileResponse(toPortablePath(relative),
                    new String(content, StandardCharsets.UTF_8), content.length,
                    Files.getLastModifiedTime(realPath).toInstant(), sha256(content));
        } catch (IOException ex) {
            throw inaccessibleFile();
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectSearchHit> search(Long userId, Long projectId, String query, Integer requestedMaxResults) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        Project project = ownedProject(userId, projectId);
        ProjectPathPolicy policy = ProjectPathPolicy.from(project, objectMapper);
        int maxResults = requestedMaxResults == null ? 20 : Math.max(1, Math.min(requestedMaxResults, properties.getMaxSearchResults()));
        if (project.getRootType() == ProjectRootType.MINIO_OBJECTS) {
            return searchObjectFiles(project, policy, query, maxResults);
        }
        ResolvedProject resolved = resolveLocal(project, policy);
        List<ProjectSearchHit> hits = new ArrayList<>();
        for (ProjectFileEntry entry : listFiles(resolved)) {
            if (hits.size() >= maxResults) {
                break;
            }
            Path file = ProjectPathGuard.resolveExistingFile(resolved.root(), entry.path());
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                if (attributes.size() > properties.getMaxFileBytes() || !isReadableTextFile(file, attributes.size())) {
                    continue;
                }
                String[] lines = new String(readBoundedContent(file, attributes.size()), StandardCharsets.UTF_8)
                        .split("\\R", -1);
                for (int index = 0; index < lines.length && hits.size() < maxResults; index++) {
                    if (lines[index].contains(query)) {
                        hits.add(new ProjectSearchHit(entry.path(), index + 1,
                                abbreviate(lines[index]), entry.sha256()));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // A changed or malformed file is skipped; it must not escape the authorized root.
            }
        }
        return hits;
    }

    private Project ownedProject(Long userId, Long projectId) {
        Project project = projects.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (project.getAccessMode() != ProjectAccessMode.READ_ONLY) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project is not read-only");
        }
        return project;
    }

    private ResolvedProject resolveLocal(Project project, ProjectPathPolicy policy) {
        if (project.getRootType() == ProjectRootType.MINIO_OBJECTS) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project object root is not a local path");
        }
        return new ResolvedProject(rootProviderFor(project.getRootType()).resolve(project), policy);
    }

    private ProjectRootProvider rootProviderFor(ProjectRootType rootType) {
        return rootProviders.stream()
                .filter(provider -> provider.supportedType() == rootType)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Project root provider is unavailable"));
    }

    private List<ProjectFileEntry> listFiles(ResolvedProject resolved) {
        List<ProjectFileEntry> files = new ArrayList<>();
        TraversalBudget budget = new TraversalBudget();
        try {
            Files.walkFileTree(resolved.root().canonicalPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(resolved.root().canonicalPath()) && Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path relative = resolved.root().canonicalPath().relativize(dir);
                    if (relative.getNameCount() > properties.getMaxTraversalDepth()) {
                        throw new ProjectTraversalLimitException("Project traversal depth exceeded");
                    }
                    if (resolved.policy().shouldSkipDirectory(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    budget.recordVisitedFile();
                    if (attrs.size() > properties.getMaxFileBytes()) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = resolved.root().canonicalPath().relativize(file);
                    if (!resolved.policy().allows(relative) || !isReadableTextFile(file, attrs.size())) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path realPath = file.toRealPath();
                    if (!realPath.startsWith(resolved.root().canonicalPath())) {
                        return FileVisitResult.CONTINUE;
                    }
                    budget.recordAdmittedBytes(attrs.size());
                    byte[] content = readBoundedContent(realPath, attrs.size());
                    files.add(new ProjectFileEntry(toPortablePath(relative), content.length,
                            Files.getLastModifiedTime(realPath).toInstant(), sha256(content)));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (ProjectTraversalLimitException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project files are unavailable", ex);
        }
        files.sort(Comparator.comparing(ProjectFileEntry::path));
        return files;
    }

    private List<ProjectFileEntry> listObjectFiles(Project project, ProjectPathPolicy policy) {
        List<ProjectFileEntry> files = new ArrayList<>();
        TraversalBudget budget = new TraversalBudget();
        for (ProjectObjectEntry entry : requireObjectStorage().readManifest(project.getRootPath())) {
            Path relative = ProjectPathGuard.parseRelative(entry.path(), "Project object path");
            if (relative.getNameCount() > properties.getMaxTraversalDepth()) {
                throw new ProjectTraversalLimitException("Project traversal depth exceeded");
            }
            if (!policy.allows(relative)) continue;
            budget.recordVisitedFile();
            if (entry.sizeBytes() > properties.getMaxFileBytes()) continue;
            budget.recordAdmittedBytes(entry.sizeBytes());
            files.add(entry.toFileEntry());
        }
        files.sort(Comparator.comparing(ProjectFileEntry::path));
        return List.copyOf(files);
    }

    private ProjectFileResponse readObjectFile(Project project, ProjectPathPolicy policy, String relativePath) {
        Path relative = ProjectPathGuard.parseRelative(relativePath, "path");
        if (!policy.allows(relative)) throw inaccessibleFile();
        String portablePath = toPortablePath(relative);
        ProjectObjectEntry entry = requireObjectStorage().readManifest(project.getRootPath()).stream()
                .filter(item -> portablePath.equals(item.path()))
                .findFirst().orElseThrow(this::inaccessibleFile);
        if (entry.sizeBytes() > properties.getMaxFileBytes()) throw inaccessibleFile();
        byte[] content = requireObjectStorage().readFile(project.getRootPath(), portablePath,
                properties.getMaxFileBytes());
        if (content.length != entry.sizeBytes() || !sha256(content).equals(entry.sha256())
                || !isReadableTextContent(content)) {
            throw inaccessibleFile();
        }
        return new ProjectFileResponse(portablePath, new String(content, StandardCharsets.UTF_8),
                content.length, entry.modifiedAt(), entry.sha256());
    }

    private List<ProjectSearchHit> searchObjectFiles(Project project, ProjectPathPolicy policy,
                                                     String query, int maxResults) {
        List<ProjectSearchHit> hits = new ArrayList<>();
        for (ProjectFileEntry entry : listObjectFiles(project, policy)) {
            if (hits.size() >= maxResults) break;
            byte[] content = requireObjectStorage().readFile(project.getRootPath(), entry.path(),
                    properties.getMaxFileBytes());
            if (content.length != entry.sizeBytes() || !sha256(content).equals(entry.sha256())
                    || !isReadableTextContent(content)) {
                continue;
            }
            String[] lines = new String(content, StandardCharsets.UTF_8).split("\\R", -1);
            for (int index = 0; index < lines.length && hits.size() < maxResults; index++) {
                if (lines[index].contains(query)) {
                    hits.add(new ProjectSearchHit(entry.path(), index + 1,
                            abbreviate(lines[index]), entry.sha256()));
                }
            }
        }
        return hits;
    }

    private boolean isReadableTextContent(byte[] content) {
        int sampleSize = Math.min(TEXT_SAMPLE_BYTES, content.length);
        for (int index = 0; index < sampleSize; index++) {
            if (content[index] == 0) return false;
        }
        return true;
    }

    private ProjectObjectStorage requireObjectStorage() {
        if (objectStorage == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Project object storage is not configured");
        }
        return objectStorage;
    }

    private boolean isReadableTextFile(Path file, long size) throws IOException {
        ByteBuffer sample = ByteBuffer.allocate((int) Math.min(TEXT_SAMPLE_BYTES, size));
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            while (sample.hasRemaining() && channel.read(sample) > 0) {
                // Read only a bounded sample; full content is read once later for the response/hash.
            }
        }
        for (byte value : sample.array()) {
            if (value == 0) {
                return false;
            }
        }
        return true;
    }

    private byte[] readBoundedContent(Path file, long expectedSize) throws IOException {
        if (expectedSize < 0 || expectedSize > properties.getMaxFileBytes() || expectedSize > Integer.MAX_VALUE) {
            throw new IOException("Project file exceeds the read budget");
        }
        ByteBuffer content = ByteBuffer.allocate((int) expectedSize);
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            while (content.hasRemaining() && channel.read(content) > 0) {
                // Fill the pre-sized buffer without allocating beyond the checked size.
            }
            if (channel.read(ByteBuffer.allocate(1)) > 0) {
                throw new IOException("Project file changed beyond the read budget");
            }
        }
        if (content.position() == content.capacity()) {
            return content.array();
        }
        byte[] truncated = new byte[content.position()];
        System.arraycopy(content.array(), 0, truncated, 0, truncated.length);
        return truncated;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String toPortablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String abbreviate(String line) {
        return line.length() <= MAX_SEARCH_LINE_LENGTH ? line : line.substring(0, MAX_SEARCH_LINE_LENGTH);
    }

    private ResponseStatusException inaccessibleFile() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Project file not found");
    }

    private record ResolvedProject(ProjectRoot root, ProjectPathPolicy policy) {
    }

    private final class TraversalBudget {
        private int visitedFiles;
        private long admittedBytes;

        void recordVisitedFile() {
            visitedFiles++;
            if (visitedFiles > properties.getMaxFiles()) {
                throw new ProjectTraversalLimitException("Project file-count budget exceeded");
            }
        }

        void recordAdmittedBytes(long bytes) {
            if (bytes > properties.getMaxTotalBytes() - admittedBytes) {
                throw new ProjectTraversalLimitException("Project byte budget exceeded");
            }
            admittedBytes += bytes;
        }
    }
}
