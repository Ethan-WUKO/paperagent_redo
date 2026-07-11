package com.yanban.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Locks the MVP-0 status/phase/outcome terminal-state vocabulary. */
class AgentTaskStateSafetyRegressionTest {

    @Test
    void everyTerminalStatusHasOnlyItsCompatibleFinalizingOutcome() {
        assertThat(AgentTaskState.completed(AgentTaskOutcome.SUCCEEDED).status()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(AgentTaskState.completed(AgentTaskOutcome.PARTIAL).status()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(AgentTaskState.completed(AgentTaskOutcome.FAILED).status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(AgentTaskState.completed(AgentTaskOutcome.CANCELLED).status()).isEqualTo(AgentTaskStatus.CANCELLED);
        assertThat(new AgentTaskState(AgentTaskStatus.STOPPED, AgentTaskPhase.FINALIZING,
                AgentTaskOutcome.CANCELLED).outcome()).isEqualTo(AgentTaskOutcome.CANCELLED);

        assertThatThrownBy(() -> new AgentTaskState(AgentTaskStatus.FAILED, AgentTaskPhase.FINALIZING,
                AgentTaskOutcome.PARTIAL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentTaskState(AgentTaskStatus.CANCELLED, AgentTaskPhase.EXECUTING,
                AgentTaskOutcome.CANCELLED)).isInstanceOf(IllegalArgumentException.class);
    }
}
