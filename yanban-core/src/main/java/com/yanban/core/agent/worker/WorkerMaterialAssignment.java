package com.yanban.core.agent.worker;

import com.yanban.core.research.ProjectRelativePath;

/** Server-assigned material path and its comparison kind. */
public record WorkerMaterialAssignment(ProjectRelativePath relativePath, WorkerMaterialType materialType)
        implements RejectsUnknownFields {
    public WorkerMaterialAssignment {
        if (relativePath == null || materialType == null) {
            throw new IllegalArgumentException("worker material assignment is incomplete");
        }
    }
}
