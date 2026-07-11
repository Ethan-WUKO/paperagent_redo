CREATE TABLE agent_artifacts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    title VARCHAR(255) NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    content LONGTEXT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_refs_json LONGTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_agent_artifacts_user_updated (user_id, status, updated_at),
    INDEX idx_agent_artifacts_session_updated (user_id, session_id, status, updated_at)
);
