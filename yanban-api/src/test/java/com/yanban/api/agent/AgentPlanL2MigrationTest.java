package com.yanban.api.agent;

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
        "spring.datasource.url=jdbc:h2:mem:agent_plan_l2_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class AgentPlanL2MigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void v35AddsBackwardCompatibleL2ColumnsAndIndexes() throws SQLException {
        assertThat(columns("agent_plans")).contains(
                "persistence_level", "lease_owner", "lease_token", "lease_fence", "lease_expires_at",
                "heartbeat_at", "checkpoint_json", "checkpoint_hash", "checkpoint_version",
                "recovery_status", "canonical_answer", "canonical_answer_hash");
        assertThat(columns("agent_plan_events")).contains("idempotency_key");
        assertThat(indexes("agent_plans")).contains("idx_agent_plans_l2_recovery");
        assertThat(indexes("agent_plan_events")).contains("uk_agent_plan_events_idempotency");
    }

    private Set<String> columns(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getColumns(null, null, table, null)) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString("COLUMN_NAME").toLowerCase());
            return names;
        }
    }

    private Set<String> indexes(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
            Set<String> names = new HashSet<>();
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                if (name != null) names.add(name.toLowerCase());
            }
            return names;
        }
    }
}
