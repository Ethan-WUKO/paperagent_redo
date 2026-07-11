package com.yanban.api.project;

import java.time.Instant;

public record ProjectFileEntry(String path, long sizeBytes, Instant modifiedAt, String sha256) {
}
