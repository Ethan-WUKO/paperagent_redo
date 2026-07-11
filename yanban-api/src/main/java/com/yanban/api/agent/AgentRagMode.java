package com.yanban.api.agent;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AgentRagMode {
    LANGCHAIN4J_AUGMENTOR;

    @JsonCreator
    public static AgentRagMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return LANGCHAIN4J_AUGMENTOR;
        }
        return LANGCHAIN4J_AUGMENTOR;
    }
}
