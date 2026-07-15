package com.yanban.api.project;

import java.time.Instant;

public record ProjectObjectEntry(String path, long sizeBytes, Instant modifiedAt, String sha256) {

    ProjectFileEntry toFileEntry() {
        return new ProjectFileEntry(path, sizeBytes, modifiedAt, sha256);
    }
}
