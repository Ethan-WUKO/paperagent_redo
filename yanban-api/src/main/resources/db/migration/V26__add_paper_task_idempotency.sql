ALTER TABLE paper_tasks
    ADD COLUMN client_request_id VARCHAR(128) NULL,
    ADD COLUMN idempotency_key VARCHAR(64) NULL;

CREATE UNIQUE INDEX uq_paper_tasks_idempotency_key ON paper_tasks (idempotency_key);
CREATE INDEX idx_paper_tasks_client_request_id ON paper_tasks (user_id, client_request_id, created_at);
