package com.yanban.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentTaskStateTest {

    @Test
    void activeStateHasNoOutcomeAndCompletedStateUsesTerminalStatus() {
        AgentTaskState active = AgentTaskState.active(AgentTaskStatus.RUNNING, AgentTaskPhase.EXECUTING);
        AgentTaskState completed = AgentTaskState.completed(AgentTaskOutcome.PARTIAL);

        assertThat(active.outcome()).isNull();
        assertThat(completed.status()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(completed.phase()).isEqualTo(AgentTaskPhase.FINALIZING);
        assertThat(completed.outcome()).isEqualTo(AgentTaskOutcome.PARTIAL);
    }

    @Test
    void nonTerminalStateCannotClaimAnOutcome() {
        assertThatThrownBy(() -> new AgentTaskState(
                AgentTaskStatus.RUNNING, AgentTaskPhase.EXECUTING, AgentTaskOutcome.SUCCEEDED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void terminalTaskStateRequiresMatchingOutcomeAndFinalizingPhase() {
        assertThatThrownBy(() -> new AgentTaskState(
                AgentTaskStatus.COMPLETED, AgentTaskPhase.FINALIZING, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentTaskState(
                AgentTaskStatus.RUNNING, AgentTaskPhase.FINALIZING, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentTaskState.active(AgentTaskStatus.COMPLETED, AgentTaskPhase.EXECUTING))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
