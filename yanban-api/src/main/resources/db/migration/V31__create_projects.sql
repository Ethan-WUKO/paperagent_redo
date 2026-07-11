CREATE TABLE projects (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    root_type VARCHAR(32) NOT NULL,
    root_path VARCHAR(1024) NOT NULL,
    canonical_root_path VARCHAR(1024) NOT NULL,
    access_mode VARCHAR(32) NOT NULL DEFAULT 'READ_ONLY',
    include_rules TEXT NOT NULL,
    ignore_rules TEXT NOT NULL,
    last_indexed_at TIMESTAMP NULL,
    index_version VARCHAR(128) NOT NULL DEFAULT 'UNINDEXED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_projects_user FOREIGN KEY (user_id) REFERENCES sys_users(id)
);

CREATE INDEX idx_projects_user_updated ON projects(user_id, updated_at);
