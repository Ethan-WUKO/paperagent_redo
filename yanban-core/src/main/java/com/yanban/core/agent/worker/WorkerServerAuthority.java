package com.yanban.core.agent.worker;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchRuntimeScope;
import java.util.List;

/** Server-only parent identity, Project scope, allowlist, and aggregate budget. */
@JsonSerialize(using = WorkerServerOnlySerializer.class)
@JsonDeserialize(using = WorkerServerOnlyDeserializer.class)
public final class WorkerServerAuthority {
    public static final String REQUIRED_READ_CAPABILITY = "research:project-read";

    private final AgentRunIdentity parentIdentity;
    private final ResearchRuntimeScope runtimeScope;
    private final List<String> parentAllowedReadTools;
    private final WorkerBudget parentBudget;

    private WorkerServerAuthority(AgentRunIdentity parentIdentity, ResearchRuntimeScope runtimeScope,
                                  List<String> parentAllowedReadTools, WorkerBudget parentBudget) {
        this.parentIdentity = parentIdentity;
        this.runtimeScope = runtimeScope;
        this.parentAllowedReadTools = parentAllowedReadTools;
        this.parentBudget = parentBudget;
    }

    public static WorkerServerAuthority serverResolved(AgentRunIdentity parentIdentity,
                                                        ResearchRuntimeScope runtimeScope,
                                                        List<String> parentAllowedReadTools,
                                                        WorkerBudget parentBudget) {
        if (parentIdentity == null || runtimeScope == null || parentBudget == null) {
            throw new IllegalArgumentException("worker server authority inputs are incomplete");
        }
        runtimeScope.requireCapability(REQUIRED_READ_CAPABILITY);
        if (parentIdentity.projectId() == null
                || parentIdentity.projectId().longValue() != runtimeScope.trustedProjectId()) {
            throw new IllegalArgumentException("parent run does not own the trusted Project");
        }
        if (parentIdentity.userId().longValue() != runtimeScope.trustedUserId()) {
            throw new IllegalArgumentException("parent run does not belong to the trusted user");
        }
        WorkerContractSupport.identifier(parentIdentity.runId(), "parent run identity");
        List<String> tools = WorkerContractSupport.sortedDistinctTools(parentAllowedReadTools,
                WorkerTaskPacket.MAX_ALLOWED_TOOLS);
        return new WorkerServerAuthority(parentIdentity, runtimeScope, tools, parentBudget);
    }

    public String parentRunId() { return parentIdentity.runId(); }
    public ProjectVersionRef projectVersion() { return runtimeScope.projectVersion(); }
    public List<String> parentAllowedReadTools() { return parentAllowedReadTools; }
    public WorkerBudget parentBudget() { return parentBudget; }
    public WorkerAccessMode workerAccessMode() { return WorkerAccessMode.READ_ONLY; }
    public boolean parentIsSingleWriter() { return true; }
    public boolean canWriteCandidate() { return false; }
    public boolean canApplyRevision() { return false; }
    public boolean canExecuteCommands() { return false; }
    public boolean canUseNetwork() { return false; }
    public boolean canCompleteParentTask() { return false; }

    long trustedProjectId() { return runtimeScope.trustedProjectId(); }
    long trustedUserId() { return runtimeScope.trustedUserId(); }

    boolean sameBoundary(WorkerServerAuthority other) {
        return other != null && trustedProjectId() == other.trustedProjectId()
                && trustedUserId() == other.trustedUserId()
                && parentRunId().equals(other.parentRunId())
                && projectVersion().equals(other.projectVersion());
    }
}
