package com.yanban.core.agent;

/** The caller must stop without writing because another owner may now hold the run. */
public class AgentPlanLeaseLostException extends IllegalStateException {
    public AgentPlanLeaseLostException(String message) {
        super(message);
    }
}
