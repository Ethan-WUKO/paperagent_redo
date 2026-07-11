package com.yanban.api.agent;

public enum AgentStrategy {
    DIRECT,
    SINGLE_STEP_REACT,
    PLAN_EXECUTE,
    PLAN_EXECUTE_WITH_REFLECTION,
    LONG_RUNNING_TOOL_TASK,
    WAIT_FOR_USER_CONFIRMATION
}
