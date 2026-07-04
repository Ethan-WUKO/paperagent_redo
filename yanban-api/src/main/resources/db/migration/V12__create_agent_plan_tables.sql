CREATE TABLE agent_plans (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    goal LONGTEXT NOT NULL,
    summary VARCHAR(500),
    status VARCHAR(32) NOT NULL,
    rag_disabled BOOLEAN NOT NULL DEFAULT FALSE,
    skill_id VARCHAR(128),
    raw_plan_json LONGTEXT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    INDEX idx_agent_plans_session_created (session_id, created_at),
    INDEX idx_agent_plans_user_created (user_id, created_at),
    CONSTRAINT fk_agent_plans_session FOREIGN KEY (session_id) REFERENCES agent_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_plans_user FOREIGN KEY (user_id) REFERENCES sys_users (id)
);

CREATE TABLE agent_plan_steps (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    step_key VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL,
    title VARCHAR(255),
    description LONGTEXT NOT NULL,
    type VARCHAR(64) NOT NULL,
    dependencies_json LONGTEXT,
    allowed_tools_json LONGTEXT,
    success_criteria LONGTEXT,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    result LONGTEXT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_plan_steps_plan_key (plan_id, step_key),
    INDEX idx_agent_plan_steps_plan_order (plan_id, sort_order),
    CONSTRAINT fk_agent_plan_steps_plan FOREIGN KEY (plan_id) REFERENCES agent_plans (id) ON DELETE CASCADE
);

CREATE TABLE agent_plan_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    step_id BIGINT,
    event_type VARCHAR(64) NOT NULL,
    payload_json LONGTEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_agent_plan_events_plan_created (plan_id, created_at),
    CONSTRAINT fk_agent_plan_events_plan FOREIGN KEY (plan_id) REFERENCES agent_plans (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_plan_events_step FOREIGN KEY (step_id) REFERENCES agent_plan_steps (id) ON DELETE SET NULL
);
