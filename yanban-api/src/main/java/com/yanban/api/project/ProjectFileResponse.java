package com.yanban.api.project;

import java.time.Instant;

public record ProjectFileResponse(String path, String content, long sizeBytes, Instant modifiedAt, String sha256) {
}
