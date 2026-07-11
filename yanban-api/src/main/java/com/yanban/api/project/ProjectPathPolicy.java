package com.yanban.api.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Locale;

final class ProjectPathPolicy {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final List<String> BLOCKED_SEGMENTS = List.of(".git", "target", "build", "node_modules", ".idea");
    // Known credential/key patterns only; unknown user files are not generically labelled sensitive.
    private static final List<String> BLOCKED_FILE_NAMES = List.of(
            "id_rsa", "id_dsa", "id_ed25519", "credentials", "credentials.json", ".env",
            ".netrc", ".npmrc", ".pypirc", "service-account.json", "secrets.yml", "secrets.yaml");
    private static final List<String> BLOCKED_SUFFIXES = List.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx");

    private final List<PathMatcher> includes;
    private final List<PathMatcher> ignores;
    private final boolean valid;

    private ProjectPathPolicy(List<PathMatcher> includes, List<PathMatcher> ignores, boolean valid) {
        this.includes = includes;
        this.ignores = ignores;
        this.valid = valid;
    }

    static ProjectPathPolicy from(Project project, ObjectMapper objectMapper) {
        try {
            return new ProjectPathPolicy(compileRules(readPersistedRules(project.getIncludeRules(), objectMapper)),
                    compileRules(readPersistedRules(project.getIgnoreRules(), objectMapper)), true);
        } catch (InvalidProjectPathException ex) {
            return new ProjectPathPolicy(List.of(), List.of(), false);
        }
    }

    boolean allows(Path relativePath) {
        if (!valid || includes.isEmpty() || isDefaultBlocked(relativePath)) {
            return false;
        }
        Path normalized = Path.of(relativePath.toString().replace('\\', '/'));
        return includes.stream().anyMatch(matcher -> matcher.matches(normalized))
                && ignores.stream().noneMatch(matcher -> matcher.matches(normalized));
    }

    boolean shouldSkipDirectory(Path relativePath) {
        if (relativePath.getNameCount() == 0 || isDefaultBlocked(relativePath)) {
            return relativePath.getNameCount() > 0;
        }
        Path childProbe = relativePath.resolve(".project-directory-probe");
        return ignores.stream().anyMatch(matcher -> matcher.matches(relativePath) || matcher.matches(childProbe));
    }

    static void validateRules(List<String> rules, String field, boolean allowEmpty) {
        if (rules == null || (!allowEmpty && rules.isEmpty())) {
            throw new InvalidProjectPathException(field + " must be present");
        }
        compileRules(rules);
    }

    private static boolean isDefaultBlocked(Path path) {
        for (Path part : path) {
            if (BLOCKED_SEGMENTS.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return BLOCKED_FILE_NAMES.contains(filename)
                || filename.startsWith(".env.")
                || BLOCKED_SUFFIXES.stream().anyMatch(filename::endsWith);
    }

    private static List<String> readPersistedRules(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            throw new InvalidProjectPathException("Persisted Project rules are missing");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                throw new InvalidProjectPathException("Persisted Project rules have an invalid type");
            }
            for (com.fasterxml.jackson.databind.JsonNode rule : node) {
                if (!rule.isTextual()) {
                    throw new InvalidProjectPathException("Persisted Project rules have an invalid type");
                }
            }
            List<String> rules = objectMapper.readValue(json, STRING_LIST);
            if (rules == null || rules.stream().anyMatch(rule -> rule == null || rule.isBlank())) {
                throw new InvalidProjectPathException("Persisted Project rules are invalid");
            }
            return rules;
        } catch (Exception ex) {
            if (ex instanceof InvalidProjectPathException invalid) {
                throw invalid;
            }
            throw new InvalidProjectPathException("Persisted Project rules are invalid", ex);
        }
    }

    private static List<PathMatcher> compileRules(List<String> rules) {
        if (rules == null || rules.stream().anyMatch(rule -> rule == null || rule.isBlank())) {
            throw new InvalidProjectPathException("Project rules are invalid");
        }
        List<PathMatcher> matchers = new java.util.ArrayList<>();
        for (String rule : rules) {
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + rule.replace('\\', '/')));
            } catch (RuntimeException ex) {
                throw new InvalidProjectPathException("Project glob rule is invalid", ex);
            }
        }
        return matchers;
    }
}
