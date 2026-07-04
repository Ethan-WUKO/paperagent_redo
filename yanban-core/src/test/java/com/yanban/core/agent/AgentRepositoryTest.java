package com.yanban.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = AgentRepositoryTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AgentRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AgentSession.class)
    @EnableJpaRepositories(basePackageClasses = AgentSessionRepository.class)
    static class TestConfig {
    }

    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final AgentToolRunRepository toolRuns;
    private final AgentSessionSummaryRepository summaries;
    private final AgentTurnRepository turns;
    private final AgentContextSnapshotRepository contextSnapshots;
    private final AgentLongTermMemoryRepository longTermMemories;

    @Autowired
    AgentRepositoryTest(AgentSessionRepository sessions,
                        AgentMessageRepository messages,
                        AgentToolRunRepository toolRuns,
                        AgentSessionSummaryRepository summaries,
                        AgentTurnRepository turns,
                        AgentContextSnapshotRepository contextSnapshots,
                        AgentLongTermMemoryRepository longTermMemories) {
        this.sessions = sessions;
        this.messages = messages;
        this.toolRuns = toolRuns;
        this.summaries = summaries;
        this.turns = turns;
        this.contextSnapshots = contextSnapshots;
        this.longTermMemories = longTermMemories;
    }

    @Test
    void insertSessionAndMessagesThenQueryBySession() {
        AgentSession session = sessions.save(new AgentSession(
                1001L,
                "测试会话",
                "deepseek",
                "deepseek-chat",
                20,
                false
        ));

        messages.save(new AgentMessage(session.getId(), 1001L, "user", "你好", null, null));
        messages.save(new AgentMessage(session.getId(), 1001L, "assistant", "你好，我是研伴。", null, null));
        toolRuns.save(new AgentToolRun(session.getId(), null, "echo", "{\"message\":\"hi\"}", "{\"message\":\"hi\"}", "SUCCESS", 12L, null));

        List<AgentMessage> savedMessages = messages.findBySessionIdOrderByCreatedAtAsc(session.getId());
        List<AgentToolRun> savedToolRuns = toolRuns.findBySessionIdOrderByCreatedAtAsc(session.getId());

        assertThat(savedMessages).hasSize(2);
        assertThat(savedMessages).extracting(AgentMessage::getRole).containsExactly("user", "assistant");
        assertThat(savedToolRuns).hasSize(1);
        assertThat(savedToolRuns.get(0).getToolName()).isEqualTo("echo");
        assertThat(sessions.findByIdAndUserId(session.getId(), 1001L)).isPresent();
    }

    @Test
    void upsertSessionSummaryThenQueryBySessionAndUser() {
        AgentSession session = sessions.save(new AgentSession(
                1002L,
                "summary session",
                "deepseek",
                "deepseek-chat",
                20,
                false
        ));
        AgentMessage message = messages.save(new AgentMessage(
                session.getId(),
                1002L,
                "user",
                "long context",
                null,
                null
        ));
        AgentSessionSummary summary = summaries.saveAndFlush(new AgentSessionSummary(
                session.getId(),
                1002L,
                "User studies GraphRAG.",
                message.getId(),
                1,
                "deepseek",
                "deepseek-chat"
        ));

        assertThat(summaries.findBySessionIdAndUserId(session.getId(), 1002L)).contains(summary);

        summary.update("User studies GraphRAG and citation coverage.", message.getId(), 2, "glm", "glm-4");
        summaries.saveAndFlush(summary);

        AgentSessionSummary updated = summaries.findBySessionIdAndUserId(session.getId(), 1002L).orElseThrow();
        assertThat(updated.getSummaryText()).isEqualTo("User studies GraphRAG and citation coverage.");
        assertThat(updated.getMessageCount()).isEqualTo(2);
        assertThat(updated.getModelProviderSnapshot()).isEqualTo("glm");
    }

    @Test
    void insertContextSnapshotThenQueryByTurnAndSession() {
        AgentSession session = sessions.save(new AgentSession(
                1003L,
                "context snapshot session",
                "deepseek",
                "deepseek-chat",
                20,
                false
        ));
        AgentMessage userMessage = messages.save(new AgentMessage(
                session.getId(),
                1003L,
                "user",
                "debug context",
                null,
                null
        ));
        AgentTurn turn = turns.saveAndFlush(new AgentTurn(session.getId(), 1003L, userMessage.getId()));
        AgentContextSnapshot snapshot = contextSnapshots.saveAndFlush(new AgentContextSnapshot(
                turn.getId(),
                session.getId(),
                1003L,
                "trace-context",
                "[{\"type\":\"recent_messages\",\"itemCount\":1,\"estimatedCharacters\":10,\"note\":\"recent\"}]",
                "[]",
                1,
                1,
                2,
                120
        ));

        assertThat(contextSnapshots.findByTurnIdAndSessionIdAndUserId(turn.getId(), session.getId(), 1003L))
                .contains(snapshot);
        assertThat(contextSnapshots.findBySessionIdAndUserIdOrderByCreatedAtDesc(
                session.getId(),
                1003L,
                PageRequest.of(0, 10)
        )).containsExactly(snapshot);
    }

    @Test
    void insertLongTermMemoryThenSoftDeleteAndQueryByStatus() {
        AgentLongTermMemory memory = longTermMemories.saveAndFlush(new AgentLongTermMemory(
                1004L,
                null,
                "USER",
                "PREFERENCE",
                "User prefers concise academic prose.",
                "[\"style\"]",
                "USER_CONFIRMED",
                null,
                java.math.BigDecimal.valueOf(0.85),
                null
        ));

        assertThat(longTermMemories.findByIdAndUserId(memory.getId(), 1004L)).contains(memory);
        assertThat(longTermMemories.findByUserIdAndStatusOrderByUpdatedAtDesc(
                1004L,
                AgentLongTermMemory.STATUS_ACTIVE,
                PageRequest.of(0, 10)
        )).containsExactly(memory);

        memory.markDeleted();
        longTermMemories.saveAndFlush(memory);

        assertThat(longTermMemories.findByUserIdAndStatusOrderByUpdatedAtDesc(
                1004L,
                AgentLongTermMemory.STATUS_DELETED,
                PageRequest.of(0, 10)
        )).containsExactly(memory);
        assertThat(memory.getDeletedAt()).isNotNull();
    }
}
