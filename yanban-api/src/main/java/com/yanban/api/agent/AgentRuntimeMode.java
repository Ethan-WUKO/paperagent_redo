package com.yanban.api.agent;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AgentRuntimeMode {
    LANGCHAIN4J;

    @JsonCreator
    public static AgentRuntimeMode from(String value) {
        return LANGCHAIN4J;
    }
}
