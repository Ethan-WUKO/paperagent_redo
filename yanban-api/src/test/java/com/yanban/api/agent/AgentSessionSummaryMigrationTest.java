package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_session_summary_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class AgentSessionSummaryMigrationTest {

    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "session_id",
            "user_id",
            "summary_text",
            "covered_message_id",
            "message_count",
            "model_provider_snapshot",
            "model_snapshot",
            "created_at",
            "updated_at"
    );

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationCreatesSessionSummaryTableColumnsAndIndexes() throws SQLException {
        assertThat(columns("agent_session_summaries")).containsAll(EXPECTED_COLUMNS);
        Set<String> indexes = indexes("agent_session_summaries");
        assertThat(indexes).contains("idx_agent_session_summaries_user_updated");
        assertThat(indexes).anyMatch(index -> index.startsWith("uq_agent_session_summaries_session"));
    }

    @Test
    void canInsertSummaryLinkedToSessionAndCoveredMessage() {
        jdbc.update("""
                INSERT INTO sys_users (username, password_hash)
                VALUES (?, ?)
                """, "summary-user", "hash");
        Long userId = jdbc.queryForObject("SELECT id FROM sys_users WHERE username = ?", Long.class, "summary-user");

        jdbc.update("""
                INSERT INTO agent_sessions
                    (user_id, title, model_provider_snapshot, model_snapshot, max_steps, rag_disabled)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, "summary session", "deepseek", "deepseek-chat", 20, false);
        Long sessionId = jdbc.queryForObject("SELECT id FROM agent_sessions WHERE title = ?", Long.class, "summary session");

        jdbc.update("""
                INSERT INTO agent_messages (session_id, user_id, role, content)
                VALUES (?, ?, ?, ?)
                """, sessionId, userId, "user", "long context");
        Long messageId = jdbc.queryForObject("SELECT id FROM agent_messages WHERE session_id = ?", Long.class, sessionId);

        jdbc.update("""
                INSERT INTO agent_session_summaries
                    (session_id, user_id, summary_text, covered_message_id, message_count, model_provider_snapshot, model_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, sessionId, userId, "User studies RAG.", messageId, 1, "deepseek", "deepseek-chat");

        SummaryRow row = jdbc.queryForObject("""
                SELECT summary_text, covered_message_id, message_count, model_provider_snapshot, model_snapshot
                FROM agent_session_summaries
                WHERE session_id = ?
                """, (rs, rowNum) -> new SummaryRow(
                rs.getString("summary_text"),
                rs.getLong("covered_message_id"),
                rs.getInt("message_count"),
                rs.getString("model_provider_snapshot"),
                rs.getString("model_snapshot")
        ), sessionId);

        assertThat(row).isNotNull();
        assertThat(row.summaryText()).isEqualTo("User studies RAG.");
        assertThat(row.coveredMessageId()).isEqualTo(messageId);
        assertThat(row.messageCount()).isEqualTo(1);
        assertThat(row.modelProviderSnapshot()).isEqualTo("deepseek");
        assertThat(row.modelSnapshot()).isEqualTo("deepseek-chat");
    }

    private Set<String> columns(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, null)) {
            Set<String> names = new java.util.HashSet<>();
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            return names;
        }
    }

    private Set<String> indexes(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            Set<String> names = new java.util.HashSet<>();
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                if (indexName != null) {
                    names.add(indexName.toLowerCase());
                }
            }
            return names;
        }
    }

    private record SummaryRow(
            String summaryText,
            Long coveredMessageId,
            int messageCount,
            String modelProviderSnapshot,
            String modelSnapshot
    ) {
    }
}
