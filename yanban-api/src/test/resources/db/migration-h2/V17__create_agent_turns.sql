CREATE TABLE agent_turns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_message_id BIGINT,
    assistant_message_id BIGINT,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_agent_turns_session_started (session_id, started_at),
    INDEX idx_agent_turns_user_status (user_id, status),
    CONSTRAINT fk_agent_turns_session FOREIGN KEY (session_id) REFERENCES agent_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_turns_user FOREIGN KEY (user_id) REFERENCES sys_users (id),
    CONSTRAINT fk_agent_turns_user_message FOREIGN KEY (user_message_id) REFERENCES agent_messages (id) ON DELETE SET NULL,
    CONSTRAINT fk_agent_turns_assistant_message FOREIGN KEY (assistant_message_id) REFERENCES agent_messages (id) ON DELETE SET NULL
);
