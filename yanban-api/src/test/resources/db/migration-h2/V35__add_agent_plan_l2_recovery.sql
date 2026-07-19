ALTER TABLE agent_plans ADD COLUMN persistence_level VARCHAR(32) NOT NULL DEFAULT 'L1_PERSISTED';
ALTER TABLE agent_plans ADD COLUMN lease_owner VARCHAR(128) NULL;
ALTER TABLE agent_plans ADD COLUMN lease_token VARCHAR(64) NULL;
ALTER TABLE agent_plans ADD COLUMN lease_fence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_plans ADD COLUMN lease_expires_at TIMESTAMP NULL;
ALTER TABLE agent_plans ADD COLUMN heartbeat_at TIMESTAMP NULL;
ALTER TABLE agent_plans ADD COLUMN checkpoint_json LONGTEXT NULL;
ALTER TABLE agent_plans ADD COLUMN checkpoint_hash VARCHAR(64) NULL;
ALTER TABLE agent_plans ADD COLUMN checkpoint_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_plans ADD COLUMN recovery_status VARCHAR(32) NOT NULL DEFAULT 'NONE';
ALTER TABLE agent_plans ADD COLUMN canonical_answer LONGTEXT NULL;
ALTER TABLE agent_plans ADD COLUMN canonical_answer_hash VARCHAR(64) NULL;

CREATE INDEX idx_agent_plans_l2_recovery
    ON agent_plans (persistence_level, status, lease_expires_at);

ALTER TABLE agent_plan_events
    ADD COLUMN idempotency_key VARCHAR(128) NULL;

CREATE UNIQUE INDEX uk_agent_plan_events_idempotency
    ON agent_plan_events (plan_id, idempotency_key);
