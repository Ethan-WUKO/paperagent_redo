package com.yanban.api.knowledge;

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
        "spring.datasource.url=jdbc:h2:mem:kb_version_governance_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class KbDocumentVersionGovernanceMigrationTest {

    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "project_id",
            "lineage_id",
            "version_no",
            "version_status",
            "source_task_type",
            "source_task_id",
            "source_artifact_id",
            "source_document_id",
            "canonical_key",
            "effective_at",
            "superseded_at",
            "deleted_at"
    );

    private static final Set<String> EXPECTED_INDEXES = Set.of(
            "idx_kb_documents_user_status_updated",
            "idx_kb_documents_lineage_version",
            "idx_kb_documents_project_status",
            "idx_kb_documents_source_task",
            "idx_kb_documents_canonical_key"
    );

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationAddsVersionGovernanceColumnsAndIndexes() throws SQLException {
        assertThat(columns("kb_documents")).containsAll(EXPECTED_COLUMNS);
        assertThat(indexes("kb_documents")).containsAll(EXPECTED_INDEXES);
    }

    @Test
    void oldStyleInsertKeepsActiveVersionDefaults() {
        jdbc.update("""
                INSERT INTO sys_users (username, password_hash)
                VALUES (?, ?)
                """, "kb-version-user", "hash");

        Long userId = jdbc.queryForObject(
                "SELECT id FROM sys_users WHERE username = ?",
                Long.class,
                "kb-version-user"
        );

        jdbc.update("""
                INSERT INTO kb_documents (user_id, filename, status, is_public)
                VALUES (?, ?, ?, ?)
                """, userId, "legacy.md", "READY", false);

        LegacyDocumentDefaults defaults = jdbc.queryForObject("""
                SELECT version_no, version_status, project_id, lineage_id, canonical_key
                FROM kb_documents
                WHERE filename = ?
                """, (rs, rowNum) -> new LegacyDocumentDefaults(
                rs.getInt("version_no"),
                rs.getString("version_status"),
                rs.getObject("project_id"),
                rs.getString("lineage_id"),
                rs.getString("canonical_key")
        ), "legacy.md");

        assertThat(defaults).isNotNull();
        assertThat(defaults.versionNo()).isEqualTo(1);
        assertThat(defaults.versionStatus()).isEqualTo("ACTIVE");
        assertThat(defaults.projectId()).isNull();
        assertThat(defaults.lineageId()).isNull();
        assertThat(defaults.canonicalKey()).isNull();
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

    private record LegacyDocumentDefaults(
            int versionNo,
            String versionStatus,
            Object projectId,
            String lineageId,
            String canonicalKey
    ) {
    }
}
