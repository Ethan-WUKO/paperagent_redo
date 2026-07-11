package com.yanban.api.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
public class LocalServerProjectRootProvider implements ProjectRootProvider {

    private final ProjectStorageProperties properties;

    public LocalServerProjectRootProvider(ProjectStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public ProjectRootType supportedType() {
        return ProjectRootType.LOCAL_SERVER_ROOT;
    }

    @Override
    public ProjectRoot resolve(Project project) {
        if (project.getRootType() != supportedType()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project root provider is not available");
        }
        try {
            Path canonicalProjectRoot = ProjectPathGuard.resolveTrustedAbsoluteDirectory(
                    project.getCanonicalRootPath(), "Project root");
            if (!properties.isAllowLocalAbsoluteProjectFolders()
                    && !canonicalProjectRoot.startsWith(configuredRoot())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project root is no longer authorized");
            }
            return new ProjectRoot(project.getId(), project.getUserId(), supportedType(), canonicalProjectRoot);
        } catch (InvalidProjectPathException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project root is unavailable", ex);
        }
    }

    Path authorizeRelativeRoot(String relativeRoot) {
        Path configuredRoot = configuredRoot();
        Path relative = ProjectPathGuard.parseRelative(relativeRoot, "rootRelativePath");
        try {
            Path candidate = configuredRoot.resolve(relative).normalize();
            if (!candidate.startsWith(configuredRoot) || ProjectPathGuard.containsLinkOrAlias(configuredRoot, relative)) {
                throw new InvalidProjectPathException("Project root escapes the configured server root");
            }
            Path canonical = candidate.toRealPath();
            if (!canonical.startsWith(configuredRoot) || !Files.isDirectory(canonical)) {
                throw new InvalidProjectPathException("Project root is outside the configured server root");
            }
            return canonical;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project root must be an existing directory", ex);
        }
    }

    Path authorizeAbsoluteProjectFolder(String projectFolder) {
        if (!properties.isAllowLocalAbsoluteProjectFolders()) {
            throw new InvalidProjectPathException(
                    "Absolute Project folders are disabled; enable only for a controlled local deployment");
        }
        return ProjectPathGuard.resolveTrustedAbsoluteDirectory(projectFolder, "projectFolder");
    }

    private Path configuredRoot() {
        if (properties.getLocalServerRoot() == null || properties.getLocalServerRoot().isBlank()) {
            throw new InvalidProjectPathException("yanban.project.local-server-root is not configured");
        }
        try {
            Path configuredRootPath = Path.of(properties.getLocalServerRoot()).toAbsolutePath().normalize();
            if (ProjectPathGuard.containsLinkOrAlias(configuredRootPath)) {
                throw new InvalidProjectPathException("Configured project root contains a filesystem alias");
            }
            Path configuredRoot = configuredRootPath.toRealPath();
            if (!Files.isDirectory(configuredRoot)) {
                throw new InvalidProjectPathException("Configured project root is not a directory");
            }
            return configuredRoot;
        } catch (IOException ex) {
            throw new InvalidProjectPathException("Configured project root is unavailable", ex);
        }
    }
}
