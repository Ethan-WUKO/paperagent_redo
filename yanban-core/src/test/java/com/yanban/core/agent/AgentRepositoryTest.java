package com.yanban.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    AgentRepositoryTest(AgentSessionRepository sessions,
                        AgentMessageRepository messages,
                        AgentToolRunRepository toolRuns) {
        this.sessions = sessions;
        this.messages = messages;
        this.toolRuns = toolRuns;
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
}
