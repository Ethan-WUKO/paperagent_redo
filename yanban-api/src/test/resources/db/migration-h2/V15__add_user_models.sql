CREATE TABLE user_models (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider_key VARCHAR(64) NOT NULL,
    provider_label VARCHAR(128) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    api_url VARCHAR(512) NULL,
    api_key_encrypted TEXT NULL,
    is_builtin BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_user_models_user FOREIGN KEY (user_id) REFERENCES sys_users (id)
);

ALTER TABLE sys_user_settings ADD COLUMN deepseek_models TEXT;
ALTER TABLE sys_user_settings ADD COLUMN glm_models TEXT;
