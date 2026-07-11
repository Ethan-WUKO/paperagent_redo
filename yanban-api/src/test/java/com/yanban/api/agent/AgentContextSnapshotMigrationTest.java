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
        "spring.datasource.url=jdbc:h2:mem:agent_context_snapshot_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class AgentContextSnapshotMigrationTest {

    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "turn_id",
            "session_id",
            "user_id",
            "trace_id",
            "sections_json",
            "dropped_items_json",
            "raw_message_count",
            "normalized_message_count",
            "context_message_count",
            "estimated_characters",
            "created_at"
    );

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationCreatesContextSnapshotTableColumnsAndIndexes() throws SQLException {
        assertThat(columns("agent_context_snapshots")).containsAll(EXPECTED_COLUMNS);
        Set<String> indexes = indexes("agent_context_snapshots");
        assertThat(indexes).contains("idx_agent_context_snapshots_session_created");
        assertThat(indexes).contains("idx_agent_context_snapshots_user_created");
        assertThat(indexes).anyMatch(index -> index.startsWith("uq_agent_context_snapshots_turn"));
    }

    @Test
    void canInsertSnapshotLinkedToTurnSessionAndUser() {
        jdbc.update("""
                INSERT INTO sys_users (username, password_hash)
                VALUES (?, ?)
                """, "snapshot-user", "hash");
        Long userId = jdbc.queryForObject("SELECT id FROM sys_users WHERE username = ?", Long.class, "snapshot-user");

        jdbc.update("""
                INSERT INTO agent_sessions
                    (user_id, title, model_provider_snapshot, model_snapshot, max_steps, rag_disabled)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, "snapshot session", "deepseek", "deepseek-chat", 20, false);
        Long sessionId = jdbc.queryForObject("SELECT id FROM agent_sessions WHERE title = ?", Long.class, "snapshot session");

        jdbc.update("""
                INSERT INTO agent_messages (session_id, user_id, role, content)
                VALUES (?, ?, ?, ?)
                """, sessionId, userId, "user", "debug context");
        Long messageId = jdbc.queryForObject("SELECT id FROM agent_messages WHERE session_id = ?", Long.class, sessionId);

        jdbc.update("""
                INSERT INTO agent_turns (session_id, user_id, user_message_id, status)
                VALUES (?, ?, ?, ?)
                """, sessionId, userId, messageId, "RUNNING");
        Long turnId = jdbc.queryForObject("SELECT id FROM agent_turns WHERE session_id = ?", Long.class, sessionId);

        jdbc.update("""
                INSERT INTO agent_context_snapshots
                    (turn_id, session_id, user_id, trace_id, sections_json, dropped_items_json,
                     raw_message_count, normalized_message_count, context_message_count, estimated_characters)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turnId, sessionId, userId, "trace-1", "[{\"type\":\"recent_messages\"}]", "[]", 1, 1, 2, 128);

        SnapshotRow row = jdbc.queryForObject("""
                SELECT turn_id, trace_id, sections_json, dropped_items_json, estimated_characters
                FROM agent_context_snapshots
                WHERE session_id = ?
                """, (rs, rowNum) -> new SnapshotRow(
                rs.getLong("turn_id"),
                rs.getString("trace_id"),
                rs.getString("sections_json"),
                rs.getString("dropped_items_json"),
                rs.getInt("estimated_characters")
        ), sessionId);

        assertThat(row).isNotNull();
        assertThat(row.turnId()).isEqualTo(turnId);
        assertThat(row.traceId()).isEqualTo("trace-1");
        assertThat(row.sectionsJson()).contains("recent_messages");
        assertThat(row.droppedItemsJson()).isEqualTo("[]");
        assertThat(row.estimatedCharacters()).isEqualTo(128);
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

    private record SnapshotRow(
            Long turnId,
            String traceId,
            String sectionsJson,
            String droppedItemsJson,
            int estimatedCharacters
    ) {
    }
}
