package com.yanban.api.project;

public interface ProjectRootProvider {

    ProjectRootType supportedType();

    ProjectRoot resolve(Project project);
}
