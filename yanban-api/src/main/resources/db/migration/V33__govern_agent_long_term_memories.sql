-- Roll out before enabling governed retrieval. Existing rows remain UNCONFIRMED and fail closed.
ALTER TABLE agent_long_term_memories
    ADD COLUMN confirmation_status VARCHAR(32) NOT NULL DEFAULT 'UNCONFIRMED' AFTER status,
    ADD COLUMN confirmed_at TIMESTAMP NULL AFTER confirmation_status,
    ADD COLUMN confirmed_source VARCHAR(64) NULL AFTER confirmed_at,
    ADD COLUMN provenance_type VARCHAR(64) NULL AFTER confirmed_source,
    ADD COLUMN provenance_ref VARCHAR(255) NULL AFTER provenance_type,
    ADD COLUMN project_version VARCHAR(64) NULL AFTER provenance_ref,
    ADD COLUMN expires_at TIMESTAMP NULL AFTER project_version,
    ADD COLUMN invalidated_at TIMESTAMP NULL AFTER expires_at,
    ADD COLUMN invalidation_reason VARCHAR(512) NULL AFTER invalidated_at;

CREATE INDEX idx_ltm_user_governed ON agent_long_term_memories
    (user_id, scope, project_id, status, confirmation_status, invalidated_at, expires_at, updated_at);

CREATE INDEX idx_ltm_project_version_governed ON agent_long_term_memories
    (user_id, project_id, project_version, scope, status, confirmation_status, invalidated_at, expires_at, updated_at);
