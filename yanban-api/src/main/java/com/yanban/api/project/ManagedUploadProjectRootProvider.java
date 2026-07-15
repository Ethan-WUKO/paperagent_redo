package com.yanban.api.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Resolves only server-owned snapshots created by {@link ProjectUploadService}. */
@Component
public class ManagedUploadProjectRootProvider implements ProjectRootProvider {

    private final ProjectStorageProperties properties;

    public ManagedUploadProjectRootProvider(ProjectStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public ProjectRootType supportedType() {
        return ProjectRootType.MANAGED_UPLOAD;
    }

    @Override
    public ProjectRoot resolve(Project project) {
        if (project.getRootType() != supportedType()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project root provider is not available");
        }
        try {
            Path storageRoot = storageRoot(false);
            Path projectRoot = ProjectPathGuard.resolveTrustedAbsoluteDirectory(
                    project.getCanonicalRootPath(), "Project root");
            if (projectRoot.equals(storageRoot) || !projectRoot.startsWith(storageRoot)) {
                throw new InvalidProjectPathException("Managed Project root is outside managed storage");
            }
            return new ProjectRoot(project.getId(), project.getUserId(), supportedType(), projectRoot);
        } catch (InvalidProjectPathException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project root is unavailable", ex);
        }
    }

    Path storageRoot(boolean create) {
        String configured = properties.getManagedStorageRoot();
        if (configured == null || configured.isBlank()) {
            throw new InvalidProjectPathException("Managed Project storage is not configured");
        }
        try {
            Path candidate = Path.of(configured).toAbsolutePath().normalize();
            if (create) {
                Files.createDirectories(candidate);
            }
            if (ProjectPathGuard.containsLinkOrAlias(candidate)) {
                throw new InvalidProjectPathException("Managed Project storage contains a filesystem alias");
            }
            Path realPath = candidate.toRealPath();
            if (!Files.isDirectory(realPath) || !Files.isWritable(realPath)) {
                throw new InvalidProjectPathException("Managed Project storage is unavailable");
            }
            return realPath;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof InvalidProjectPathException invalid) {
                throw invalid;
            }
            throw new InvalidProjectPathException("Managed Project storage is unavailable", ex);
        }
    }
}
