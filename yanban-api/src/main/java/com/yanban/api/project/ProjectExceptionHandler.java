package com.yanban.api.project;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ProjectController.class)
class ProjectExceptionHandler {

    @ExceptionHandler(InvalidProjectPathException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProjectErrorResponse invalidPath() {
        return new ProjectErrorResponse("INVALID_PROJECT_PATH", "Invalid Project path");
    }

    @ExceptionHandler(ProjectTraversalLimitException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    ProjectErrorResponse traversalLimit() {
        return new ProjectErrorResponse("PROJECT_LIMIT_EXCEEDED", "Project traversal limit exceeded");
    }
}
