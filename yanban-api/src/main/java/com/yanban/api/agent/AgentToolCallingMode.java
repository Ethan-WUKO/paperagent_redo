package com.yanban.api.agent;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AgentToolCallingMode {
    LANGCHAIN4J_TOOL_BINDING;

    @JsonCreator
    public static AgentToolCallingMode from(String value) {
        return LANGCHAIN4J_TOOL_BINDING;
    }
}
