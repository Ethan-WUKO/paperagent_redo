package com.yanban.api.agent;

import org.springframework.stereotype.Component;

/**
 * One bounded repair checkpoint. MVP deliberately does not invent a second tool policy,
 * capability, Project identity, or budget; a later runtime may use this checkpoint to run
 * one explicitly authorised repair turn.
 */
@Component
public class CompletionReflection {
    private static final String PROJECT_EVIDENCE_REPAIR_INSTRUCTION = """
            This is a bounded completion-repair turn for an authenticated read-only Project.
            The previous attempt did not capture a current authorized file read/search observation.
            Before producing the final answer, call project_read_file or project_search for relevant
            Project-relative data. project_manifest is inventory only and cannot by itself satisfy
            the Project file-evidence requirement. Ground the answer only in retrieved observations,
            state any remaining limitation, and do not request or imply write or command authority.
            """;

    public boolean mayAttempt(AgentRuntimeRequest request, CompletionVerification verification, AgentRuntimeResult result) {
        return verification != null
                && verification.repairable()
                && verification.reflectionAttempts() == 0
                && request != null
                && result != null
                && result.runtimeStopSignal() == AgentRuntimeStopSignal.NONE
                && (request.strategy() == AgentStrategy.DIRECT || request.strategy() == AgentStrategy.SINGLE_STEP_REACT)
                && result.steps() < request.maxSteps()
                && hasRepairAuthority(request, result);
    }

    /** Returns the exact original request: reflection is not an authority-escalation path. */
    public AgentRuntimeRequest preserveAuthority(AgentRuntimeRequest request) {
        return request;
    }

    public AgentRuntimeRequest repairRequest(AgentRuntimeRequest request, AgentRuntimeResult first) {
        int remainingSteps = request.maxSteps() - Math.max(0, first.steps());
        int remainingTools = Math.max(0, request.toolPolicy().maxToolCalls() - first.toolTrace().size());
        AgentRuntimeRequest repair = preserveAuthority(request).withReducedBudget(remainingSteps, remainingTools);
        return request.projectContext() == null
                ? repair
                : repair.withAdditionalSystemInstruction(PROJECT_EVIDENCE_REPAIR_INSTRUCTION);
    }

    private boolean hasRepairAuthority(AgentRuntimeRequest request, AgentRuntimeResult result) {
        if (request.projectContext() == null) {
            return request.toolPolicy().allowedTools().isEmpty()
                    || request.toolPolicy().maxToolCalls() > result.toolTrace().size();
        }
        if (request.toolPolicy().maxToolCalls() <= result.toolTrace().size()) return false;
        return request.toolPolicy().allowedTools().stream().anyMatch(tool ->
                "project_read_file".equals(tool) || "project_search".equals(tool));
    }
}
