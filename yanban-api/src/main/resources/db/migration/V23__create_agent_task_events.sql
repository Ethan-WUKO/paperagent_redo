CREATE TABLE agent_task_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_type VARCHAR(64) NOT NULL,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    stage VARCHAR(64),
    status VARCHAR(32),
    message VARCHAR(500),
    payload_json LONGTEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_agent_task_events_task_created (task_type, task_id, created_at),
    INDEX idx_agent_task_events_user_created (user_id, created_at),
    INDEX idx_agent_task_events_event_created (event_type, created_at),
    CONSTRAINT fk_agent_task_events_user FOREIGN KEY (user_id) REFERENCES sys_users (id)
);
