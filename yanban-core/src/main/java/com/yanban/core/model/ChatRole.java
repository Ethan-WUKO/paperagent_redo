package com.yanban.core.model;

public enum ChatRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String value;

    ChatRole(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
