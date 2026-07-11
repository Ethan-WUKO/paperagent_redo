package com.yanban.api.project;

public record ProjectSearchHit(String path, int lineNumber, String line, String sha256) {
}
