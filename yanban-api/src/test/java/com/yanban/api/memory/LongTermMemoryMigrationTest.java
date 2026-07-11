package com.yanban.api.memory;

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
        "spring.datasource.url=jdbc:h2:mem:long_term_memory_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class LongTermMemoryMigrationTest {

    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "user_id",
            "project_id",
            "scope",
            "memory_type",
            "content",
            "tags_json",
            "source_type",
            "source_ref_id",
            "confidence",
            "status",
            "supersedes_memory_id",
            "superseded_by_memory_id",
            "created_at",
            "updated_at",
            "deleted_at"
    );

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationCreatesLongTermMemoryColumnsAndIndexes() throws SQLException {
        assertThat(columns("agent_long_term_memories")).containsAll(EXPECTED_COLUMNS);
        Set<String> indexes = indexes("agent_long_term_memories");
        assertThat(indexes).contains(
                "idx_agent_long_term_memories_user_status_updated",
                "idx_agent_long_term_memories_user_scope_type",
                "idx_agent_long_term_memories_project_status"
        );
    }

    @Test
    void canInsertCorrectAndSoftDeleteMemory() {
        jdbc.update("""
                INSERT INTO sys_users (username, password_hash)
                VALUES (?, ?)
                """, "memory-user", "hash");
        Long userId = jdbc.queryForObject("SELECT id FROM sys_users WHERE username = ?", Long.class, "memory-user");

        jdbc.update("""
                INSERT INTO agent_long_term_memories
                    (user_id, scope, memory_type, content, tags_json, source_type, confidence, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, "USER", "PREFERENCE", "User prefers concise prose.", "[\"style\"]",
                "USER_CONFIRMED", 0.85, "ACTIVE");
        Long firstId = jdbc.queryForObject("SELECT id FROM agent_long_term_memories WHERE user_id = ?", Long.class, userId);

        jdbc.update("""
                INSERT INTO agent_long_term_memories
                    (user_id, scope, memory_type, content, source_type, source_ref_id, confidence, status, supersedes_memory_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, "USER", "PREFERENCE", "User prefers detailed prose.", "USER_CORRECTED",
                String.valueOf(firstId), 0.9, "ACTIVE", firstId);
        Long secondId = jdbc.queryForObject("""
                SELECT id FROM agent_long_term_memories
                WHERE user_id = ? AND supersedes_memory_id = ?
                """, Long.class, userId, firstId);

        jdbc.update("""
                UPDATE agent_long_term_memories
                SET status = ?, superseded_by_memory_id = ?
                WHERE id = ?
                """, "SUPERSEDED", secondId, firstId);
        jdbc.update("""
                UPDATE agent_long_term_memories
                SET status = ?, deleted_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, "DELETED", secondId);

        Integer deletedCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_long_term_memories
                WHERE user_id = ? AND status = ?
                """, Integer.class, userId, "DELETED");
        assertThat(deletedCount).isEqualTo(1);
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
}
