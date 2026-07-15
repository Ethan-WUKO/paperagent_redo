-- H2 mirror of the production migration. Legacy rows remain fail-closed.
ALTER TABLE agent_long_term_memories ADD COLUMN confirmation_status VARCHAR(32) NOT NULL DEFAULT 'UNCONFIRMED';
ALTER TABLE agent_long_term_memories ADD COLUMN confirmed_at TIMESTAMP NULL;
ALTER TABLE agent_long_term_memories ADD COLUMN confirmed_source VARCHAR(64) NULL;
ALTER TABLE agent_long_term_memories ADD COLUMN provenance_type VARCHAR(64) NULL;
ALTER TABLE agent_long_term_memories ADD COLUMN provenance_ref VARCHAR(255) NULL;
ALTER TABLE agent_long_term_memories ADD COLUMN project_version VARCHAR(64) NULL;
ALTER TABLE agent_long_term_memories ADD COLUMN expires_at TIMESTAMP NULL;
ALTER TABLE agent_long_term_memories ADD COLUMN invalidated_at TIMESTAMP NULL;
ALTER TABLE agent_long_term_memories ADD COLUMN invalidation_reason VARCHAR(512) NULL;

CREATE INDEX idx_ltm_user_governed ON agent_long_term_memories
    (user_id, scope, project_id, status, confirmation_status, invalidated_at, expires_at, updated_at);

CREATE INDEX idx_ltm_project_version_governed ON agent_long_term_memories
    (user_id, project_id, project_version, scope, status, confirmation_status, invalidated_at, expires_at, updated_at);
