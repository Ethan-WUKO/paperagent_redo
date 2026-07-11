package com.yanban.api.project;

class InvalidProjectPathException extends RuntimeException {

    InvalidProjectPathException(String message) {
        super(message);
    }

    InvalidProjectPathException(String message, Throwable cause) {
        super(message, cause);
    }
}
