CREATE TABLE agent_session_summaries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    summary_text LONGTEXT NOT NULL,
    covered_message_id BIGINT,
    message_count INT NOT NULL DEFAULT 0,
    model_provider_snapshot VARCHAR(64),
    model_snapshot VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_agent_session_summaries_session (session_id),
    INDEX idx_agent_session_summaries_user_updated (user_id, updated_at),
    CONSTRAINT fk_agent_session_summaries_session FOREIGN KEY (session_id) REFERENCES agent_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_session_summaries_user FOREIGN KEY (user_id) REFERENCES sys_users (id),
    CONSTRAINT fk_agent_session_summaries_covered_message FOREIGN KEY (covered_message_id) REFERENCES agent_messages (id) ON DELETE SET NULL
);
