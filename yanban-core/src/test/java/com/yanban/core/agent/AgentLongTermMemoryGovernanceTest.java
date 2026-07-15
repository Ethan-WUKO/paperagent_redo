package com.yanban.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentLongTermMemoryGovernanceTest {

    @Test
    void exposesOnlyTheMinimumTrustedConfirmationAndProvenanceVocabulary() {
        assertThat(AgentLongTermMemory.CONFIRMED_SOURCE_USER_ACTION).isEqualTo("USER_ACTION");
        assertThat(AgentLongTermMemory.PROVENANCE_USER_MESSAGE).isEqualTo("USER_MESSAGE");
        assertThat(AgentLongTermMemory.PROVENANCE_USER_SETTINGS_ACTION).isEqualTo("USER_SETTINGS_ACTION");
    }

    @Test
    void newAndCorrectedRowsRemainUnconfirmedUntilAnExplicitGovernanceFlowRuns() {
        AgentLongTermMemory memory = new AgentLongTermMemory(
                42L,
                null,
                AgentLongTermMemory.SCOPE_USER,
                AgentLongTermMemory.TYPE_FACT,
                "User prefers concise explanations.",
                "[\"style\"]",
                AgentLongTermMemory.SOURCE_USER_CONFIRMED,
                "message:1",
                BigDecimal.valueOf(0.9),
                null);

        assertThat(memory.getConfirmationStatus()).isEqualTo(AgentLongTermMemory.CONFIRMATION_UNCONFIRMED);
        assertThat(memory.getConfirmedAt()).isNull();
        assertThat(memory.getConfirmedSource()).isNull();
        assertThat(memory.getProvenanceType()).isNull();
        assertThat(memory.getProvenanceRef()).isNull();
        assertThat(memory.getProjectVersion()).isNull();
        assertThat(memory.getExpiresAt()).isNull();
        assertThat(memory.getInvalidatedAt()).isNull();
        assertThat(memory.getInvalidationReason()).isNull();
    }

    @Test
    void explicitGovernanceTransitionsSetOnlyServerProvidedFields() {
        AgentLongTermMemory memory = new AgentLongTermMemory(
                42L, null, AgentLongTermMemory.SCOPE_USER, AgentLongTermMemory.TYPE_FACT,
                "User prefers concise explanations.", null, AgentLongTermMemory.SOURCE_USER_CONFIRMED,
                null, BigDecimal.valueOf(0.9), null);
        Instant confirmedAt = Instant.parse("2026-07-15T00:00:00Z");

        memory.confirm(confirmedAt, AgentLongTermMemory.CONFIRMED_SOURCE_USER_ACTION,
                AgentLongTermMemory.PROVENANCE_USER_SETTINGS_ACTION, "memory-settings:1:confirm");

        assertThat(memory.getConfirmationStatus()).isEqualTo(AgentLongTermMemory.CONFIRMATION_CONFIRMED);
        assertThat(memory.getConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(memory.getProvenanceRef()).isEqualTo("memory-settings:1:confirm");

        memory.reject();
        assertThat(memory.getConfirmationStatus()).isEqualTo(AgentLongTermMemory.CONFIRMATION_REJECTED);
        assertThat(memory.getConfirmedAt()).isNull();
        assertThat(memory.getConfirmedSource()).isNull();
        assertThat(memory.getProvenanceType()).isNull();
        assertThat(memory.getProvenanceRef()).isNull();
    }
}
