package com.yanban.core.tool;

public class DuplicateToolException extends RuntimeException {
    public DuplicateToolException(String name) {
        super("Duplicate tool: " + name);
    }
}
