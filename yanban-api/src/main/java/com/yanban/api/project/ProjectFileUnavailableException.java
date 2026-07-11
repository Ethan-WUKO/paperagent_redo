package com.yanban.api.project;

class ProjectFileUnavailableException extends RuntimeException {

    ProjectFileUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    ProjectFileUnavailableException(String message) {
        super(message);
    }
}
