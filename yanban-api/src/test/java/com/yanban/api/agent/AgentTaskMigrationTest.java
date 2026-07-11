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
        "spring.datasource.url=jdbc:h2:mem:agent_task_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class AgentTaskMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationAddsAgentTasksTableWithCoreColumns() throws SQLException {
        assertThat(columns("agent_tasks")).contains(
                "task_type",
                "source",
                "source_id",
                "status",
                "project_id",
                "client_request_id",
                "current_stage"
        );
    }

    @Test
    void migrationEnforcesUniqueMirrorPerTaskTypeAndSource() {
        jdbc.update("""
                INSERT INTO sys_users (username, password_hash)
                VALUES (?, ?)
                """, "agent-task-user", "hash");
        Long userId = jdbc.queryForObject(
                "SELECT id FROM sys_users WHERE username = ?",
                Long.class,
                "agent-task-user"
        );

        jdbc.update("""
                INSERT INTO agent_tasks
                    (user_id, task_type, source, source_id, status, current_stage)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, "LITERATURE_SEARCH", "LITERATURE_SEARCH_TASK", 42L, "PENDING", "QUEUED");

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM agent_tasks
                WHERE user_id = ? AND task_type = ? AND source = ? AND source_id = ?
                """, Integer.class, userId, "LITERATURE_SEARCH", "LITERATURE_SEARCH_TASK", 42L);
        assertThat(count).isEqualTo(1);

        try {
            jdbc.update("""
                    INSERT INTO agent_tasks
                        (user_id, task_type, source, source_id, status, current_stage)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, userId, "LITERATURE_SEARCH", "LITERATURE_SEARCH_TASK", 42L, "RUNNING", "SEARCHING");
        } catch (Exception ex) {
            assertThat(ex.getMessage()).containsIgnoringCase("unique");
            return;
        }
        throw new AssertionError("Expected unique constraint violation");
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
}
