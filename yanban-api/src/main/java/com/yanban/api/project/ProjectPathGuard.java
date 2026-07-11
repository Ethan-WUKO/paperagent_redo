package com.yanban.api.project;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

final class ProjectPathGuard {

    private ProjectPathGuard() {
    }

    static Path parseRelative(String rawPath, String field) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new InvalidProjectPathException(field + " is required");
        }
        final Path path;
        try {
            path = Path.of(rawPath);
        } catch (RuntimeException ex) {
            throw new InvalidProjectPathException(field + " is invalid", ex);
        }
        if (path.isAbsolute() || rawPath.startsWith("/") || rawPath.startsWith("\\") || rawPath.matches("^[A-Za-z]:.*")) {
            throw new InvalidProjectPathException(field + " must be relative");
        }
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                throw new InvalidProjectPathException(field + " must not contain '..'");
            }
        }
        Path normalized = path.normalize();
        if (normalized.getNameCount() == 0 || normalized.toString().equals(".") || normalized.startsWith("..")) {
            throw new InvalidProjectPathException(field + " is invalid");
        }
        return normalized;
    }

    static Path resolveTrustedAbsoluteDirectory(String rawPath, String field) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new InvalidProjectPathException(field + " is required");
        }
        if (isWindows() && (rawPath.startsWith("\\\\") || rawPath.startsWith("//"))) {
            throw new InvalidProjectPathException(field + " must be a local absolute directory");
        }
        final Path input;
        try {
            input = Path.of(rawPath);
        } catch (RuntimeException ex) {
            throw new InvalidProjectPathException(field + " is invalid", ex);
        }
        if (!input.isAbsolute()) {
            throw new InvalidProjectPathException(field + " must be absolute");
        }
        Path candidate = input.toAbsolutePath().normalize();
        try {
            if (containsLinkOrAlias(candidate)) {
                throw new InvalidProjectPathException("Project folder contains a filesystem alias");
            }
            Path realPath = candidate.toRealPath();
            if (!candidate.equals(realPath) || !Files.isDirectory(realPath) || !Files.isReadable(realPath)) {
                throw new InvalidProjectPathException("Project folder must be a readable directory without aliases");
            }
            try (DirectoryStream<Path> ignored = Files.newDirectoryStream(realPath)) {
                // Opening the directory verifies that the Project process can actually read it.
            }
            return realPath;
        } catch (IOException ex) {
            throw new InvalidProjectPathException("Project folder must be an existing readable directory", ex);
        }
    }

    static Path resolveExistingFile(ProjectRoot root, String relativePath) {
        Path relative = parseRelative(relativePath, "path");
        try {
            if (containsLinkOrAlias(root.canonicalPath(), relative)) {
                throw new InvalidProjectPathException("Links and filesystem aliases are not readable from a Project");
            }
            Path candidate = root.canonicalPath().resolve(relative).normalize();
            if (!candidate.startsWith(root.canonicalPath())) {
                throw new InvalidProjectPathException("Path escapes Project root");
            }
            Path realPath = candidate.toRealPath();
            if (!realPath.startsWith(root.canonicalPath())) {
                throw new InvalidProjectPathException("Path escapes Project root");
            }
            if (!Files.isRegularFile(realPath)) {
                throw new ProjectFileUnavailableException("Project path is not a readable file");
            }
            return realPath;
        } catch (IOException ex) {
            throw new ProjectFileUnavailableException("Project file is unavailable", ex);
        }
    }

    static boolean containsLinkOrAlias(Path root, Path relative) throws IOException {
        if (containsLinkOrAlias(root)) {
            return true;
        }
        Path current = root.toAbsolutePath().normalize();
        for (Path part : relative) {
            current = current.resolve(part);
            if (isLinkOrAlias(current)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsLinkOrAlias(Path path) throws IOException {
        Path absolutePath = path.toAbsolutePath().normalize();
        Path current = absolutePath.getRoot();
        for (Path part : absolutePath) {
            current = current.resolve(part);
            if (isLinkOrAlias(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLinkOrAlias(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        // Windows directory junctions are reparse points: they are not reported by
        // Files.isSymbolicLink(), but NIO exposes them as "other" with NOFOLLOW_LINKS.
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
