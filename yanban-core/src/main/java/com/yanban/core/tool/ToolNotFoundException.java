package com.yanban.core.tool;

public class ToolNotFoundException extends RuntimeException {
    public ToolNotFoundException(String name) {
        super("Tool not found: " + name);
    }
}
