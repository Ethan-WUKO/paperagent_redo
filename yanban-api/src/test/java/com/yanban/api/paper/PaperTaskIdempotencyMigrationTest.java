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
        "spring.datasource.url=jdbc:h2:mem:paper_task_idempotency_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class PaperTaskIdempotencyMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationAddsPaperTaskIdempotencyColumns() throws SQLException {
        assertThat(columns("paper_tasks")).contains("client_request_id", "idempotency_key");
    }

    @Test
    void migrationEnforcesUniqueIdempotencyKey() {
        jdbc.update("""
                INSERT INTO sys_users (username, password_hash)
                VALUES (?, ?)
                """, "paper-idempotency-user", "hash");
        Long userId = jdbc.queryForObject(
                "SELECT id FROM sys_users WHERE username = ?",
                Long.class,
                "paper-idempotency-user"
        );

        jdbc.update("""
                INSERT INTO paper_tasks
                    (user_id, title, source_filename, object_key, status, target_language, current_stage, client_request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, "demo", "main.tex", "paper/main.tex", "PENDING", "zh", "UPLOAD_RECEIVED", "req-1", "idem-1");

        try {
            jdbc.update("""
                    INSERT INTO paper_tasks
                        (user_id, title, source_filename, object_key, status, target_language, current_stage, client_request_id, idempotency_key)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, userId, "demo-2", "main2.tex", "paper/main2.tex", "PENDING", "zh", "UPLOAD_RECEIVED", "req-1", "idem-1");
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
