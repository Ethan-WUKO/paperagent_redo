CREATE TABLE agent_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255),
    model_provider_snapshot VARCHAR(64) NOT NULL,
    model_snapshot VARCHAR(128) NOT NULL,
    max_steps INT NOT NULL DEFAULT 20,
    rag_disabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_agent_sessions_user_created (user_id, created_at),
    CONSTRAINT fk_agent_sessions_user FOREIGN KEY (user_id) REFERENCES sys_users (id)
);

CREATE TABLE agent_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT,
    tool_calls_json LONGTEXT,
    paper_task_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_agent_messages_session_created (session_id, created_at),
    INDEX idx_agent_messages_user_created (user_id, created_at),
    CONSTRAINT fk_agent_messages_session FOREIGN KEY (session_id) REFERENCES agent_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_messages_user FOREIGN KEY (user_id) REFERENCES sys_users (id)
);

CREATE TABLE agent_tool_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    message_id BIGINT,
    tool_name VARCHAR(128) NOT NULL,
    input_json LONGTEXT,
    output_json LONGTEXT,
    status VARCHAR(32) NOT NULL,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_agent_tool_runs_session_created (session_id, created_at),
    CONSTRAINT fk_agent_tool_runs_session FOREIGN KEY (session_id) REFERENCES agent_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_tool_runs_message FOREIGN KEY (message_id) REFERENCES agent_messages (id) ON DELETE SET NULL
);
