package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:project_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class ProjectMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationCreatesProjectAuthorizationColumnsAndOwnerIndex() throws SQLException {
        assertThat(columns("projects")).contains(
                "user_id", "root_type", "root_path", "canonical_root_path", "access_mode",
                "include_rules", "ignore_rules", "index_version"
        );
        assertThat(indexes("projects")).contains("idx_projects_user_updated");
    }

    private Set<String> columns(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, null)) {
            Set<String> names = new HashSet<>();
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            return names;
        }
    }

    private Set<String> indexes(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                ResultSet rs = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            Set<String> names = new HashSet<>();
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name.toLowerCase());
                }
            }
            return names;
        }
    }
}
