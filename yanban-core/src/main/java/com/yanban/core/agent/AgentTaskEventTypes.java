package com.yanban.core.agent;

public final class AgentTaskEventTypes {

    public static final String TASK_CREATED = "TASK_CREATED";
    public static final String TASK_RUNNING = "TASK_RUNNING";
    public static final String TASK_PAUSED = "TASK_PAUSED";
    public static final String TASK_RESUMED = "TASK_RESUMED";
    public static final String STAGE_CHANGED = "STAGE_CHANGED";
    public static final String TASK_PROGRESS = "TASK_PROGRESS";
    public static final String TASK_COMPLETED = "TASK_COMPLETED";
    public static final String TASK_FAILED = "TASK_FAILED";
    public static final String TASK_CANCEL_REQUESTED = "TASK_CANCEL_REQUESTED";
    public static final String TASK_CANCELLING = "TASK_CANCELLING";
    public static final String TASK_CANCELLED = "TASK_CANCELLED";
    public static final String TASK_TIMED_OUT = "TASK_TIMED_OUT";
    public static final String TASK_REQUEUED = "TASK_REQUEUED";
    public static final String TASK_MANUAL_REQUEUED = "TASK_MANUAL_REQUEUED";
    public static final String ARTIFACT_MARKED_PARTIAL = "ARTIFACT_MARKED_PARTIAL";

    private AgentTaskEventTypes() {
    }
}
