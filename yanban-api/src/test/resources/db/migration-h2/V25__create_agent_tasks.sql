CREATE TABLE agent_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    task_type VARCHAR(64) NOT NULL,
    source VARCHAR(64) NOT NULL,
    source_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    strategy VARCHAR(64) NULL,
    client_request_id VARCHAR(128) NULL,
    title VARCHAR(255) NULL,
    input_summary VARCHAR(1000) NULL,
    progress_percent INT NULL,
    current_stage VARCHAR(64) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    cancellation_reason VARCHAR(500) NULL,
    retry_count INT NULL,
    max_retries INT NULL,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_tasks_source UNIQUE (task_type, source, source_id),
    CONSTRAINT fk_agent_tasks_user FOREIGN KEY (user_id) REFERENCES sys_users (id)
);

CREATE INDEX idx_agent_tasks_user_created ON agent_tasks (user_id, created_at);
CREATE INDEX idx_agent_tasks_task_status ON agent_tasks (task_type, status, updated_at);
CREATE INDEX idx_agent_tasks_source ON agent_tasks (source, source_id);
