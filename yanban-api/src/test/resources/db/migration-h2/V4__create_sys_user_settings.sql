CREATE TABLE sys_user_settings (
    user_id BIGINT NOT NULL,
    default_provider VARCHAR(64) NOT NULL DEFAULT 'deepseek',
    deepseek_api_key_encrypted TEXT NULL,
    deepseek_model VARCHAR(128) NOT NULL DEFAULT 'deepseek-chat',
    deepseek_temperature DECIMAL(4,2) NOT NULL DEFAULT 0.70,
    max_steps INT NOT NULL DEFAULT 20,
    rag_default_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_sys_user_settings_user FOREIGN KEY (user_id) REFERENCES sys_users (id)
);
