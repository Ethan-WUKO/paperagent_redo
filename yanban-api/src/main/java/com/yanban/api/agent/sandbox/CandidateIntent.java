package com.yanban.api.agent.sandbox;

import com.yanban.core.agent.sandbox.CandidateTextPayload;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Explicit, authority-free full-text replacement intent. It is never inferred from assistant text
 * and becomes a Candidate only after server-side Project and Evidence validation.
 */
public record CandidateIntent(long projectId, ProjectVersionRef projectVersion, List<FileIntent> changes) {
    public enum Type { ADD, MODIFY, DELETE }

    public record FileIntent(Type type, ProjectRelativePath relativePath, FileHash baseFileHash,
                             String replacementText, List<String> evidenceRefIds) {
        public FileIntent {
            if (type == null || relativePath == null || evidenceRefIds == null || evidenceRefIds.isEmpty()) {
                throw new IllegalArgumentException("candidate intent file change is incomplete");
            }
            if (type == Type.ADD && baseFileHash != null) {
                throw new IllegalArgumentException("ADD intent forbids a base hash");
            }
            if (type != Type.ADD && baseFileHash == null) {
                throw new IllegalArgumentException(type + " intent requires a base hash");
            }
            if (type == Type.DELETE && replacementText != null) {
                throw new IllegalArgumentException("DELETE intent forbids replacement text");
            }
            if (type != Type.DELETE && replacementText == null) {
                throw new IllegalArgumentException(type + " intent requires full replacement text");
            }
            if (replacementText != null) {
                CandidateTextPayload.fromText(replacementText);
            }
            Set<String> distinct = new LinkedHashSet<>();
            for (String id : evidenceRefIds) {
                if (id == null || id.isBlank() || !distinct.add(id)) {
                    throw new IllegalArgumentException("candidate intent evidence ids must be non-blank and distinct");
                }
            }
            evidenceRefIds = List.copyOf(distinct);
        }

        public long replacementUtf8Bytes() {
            return replacementText == null ? 0 : CandidateTextPayload.fromText(replacementText).utf8Bytes();
        }
    }

    public CandidateIntent {
        if (projectId < 1 || projectVersion == null || changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("candidate intent requires Project identity and file changes");
        }
        List<FileIntent> sorted = new ArrayList<>(changes.size());
        Set<String> paths = new HashSet<>();
        for (FileIntent change : changes) {
            if (change == null || !paths.add(change.relativePath().value().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("candidate intent contains duplicate target paths");
            }
            sorted.add(change);
        }
        sorted.sort(Comparator.comparing(change -> change.relativePath().value()));
        changes = List.copyOf(sorted);
    }

    public int evidenceRefCount() {
        return changes.stream().mapToInt(change -> change.evidenceRefIds().size()).sum();
    }

    public long candidateUtf8Bytes() {
        return changes.stream().mapToLong(FileIntent::replacementUtf8Bytes).sum();
    }
}
