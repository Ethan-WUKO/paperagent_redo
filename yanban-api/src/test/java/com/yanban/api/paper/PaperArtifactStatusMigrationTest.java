package com.yanban.api.paper;

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
        "spring.datasource.url=jdbc:h2:mem:paper_artifact_status_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class PaperArtifactStatusMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationAddsArtifactStatusColumn() throws SQLException {
        assertThat(columns("paper_task_artifacts")).contains("artifact_status");
    }

    @Test
    void legacyInsertGetsCompletedArtifactStatusByDefault() {
        jdbc.update("""
                INSERT INTO sys_users (username, password_hash)
                VALUES (?, ?)
                """, "paper-artifact-user", "hash");
        Long userId = jdbc.queryForObject(
                "SELECT id FROM sys_users WHERE username = ?",
                Long.class,
                "paper-artifact-user"
        );

        jdbc.update("""
                INSERT INTO paper_tasks
                    (user_id, title, source_filename, object_key, status, target_language, current_stage)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, userId, "demo", "main.tex", "paper/main.tex", "RUNNING", "zh", "ASSEMBLE");
        Long taskId = jdbc.queryForObject(
                "SELECT id FROM paper_tasks WHERE user_id = ? AND title = ?",
                Long.class,
                userId,
                "demo"
        );

        jdbc.update("""
                INSERT INTO paper_task_artifacts
                    (task_id, type, object_key, version, metadata_json)
                VALUES (?, ?, ?, ?, ?)
                """, taskId, "review_report", "paper/review.md", 1, "{\"size\":12}");

        String status = jdbc.queryForObject("""
                SELECT artifact_status
                FROM paper_task_artifacts
                WHERE task_id = ? AND type = ?
                """, String.class, taskId, "review_report");
        assertThat(status).isEqualTo("COMPLETED");
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
