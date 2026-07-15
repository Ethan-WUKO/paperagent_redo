package com.yanban.api.project;

import java.util.List;

public record ProjectObjectManifest(String schema, List<ProjectObjectEntry> files) {

    static final String SCHEMA = "yanban-project-objects-v1";

    public ProjectObjectManifest(List<ProjectObjectEntry> files) {
        this(SCHEMA, files == null ? List.of() : List.copyOf(files));
    }
}
