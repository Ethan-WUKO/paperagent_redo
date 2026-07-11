package com.yanban.api.agent;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AgentMemoryMode {
    CONTEXT_PACKER;

    @JsonCreator
    public static AgentMemoryMode from(String value) {
        return CONTEXT_PACKER;
    }
}
