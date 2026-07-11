CREATE TABLE agent_experiment_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NULL,
    runtime_mode VARCHAR(64) NOT NULL,
    rag_mode VARCHAR(64) NOT NULL,
    memory_mode VARCHAR(64) NOT NULL,
    tool_calling_mode VARCHAR(64) NOT NULL,
    success BOOLEAN NOT NULL,
    latency_ms BIGINT NULL,
    retrieved_chunk_count INT NULL,
    memory_window_size INT NULL,
    eval_record_version INT NOT NULL DEFAULT 1,
    debug_flags_json LONGTEXT NULL,
    tool_trace_json LONGTEXT NULL,
    memory_window_json LONGTEXT NULL,
    retrieved_chunks_json LONGTEXT NULL,
    final_citations_json LONGTEXT NULL,
    error_message VARCHAR(1000) NULL,
    fallback_reason VARCHAR(500) NULL,
    answer_preview VARCHAR(4000) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE INDEX idx_agent_experiment_records_session_user_created
    ON agent_experiment_records (session_id, user_id, created_at);

CREATE INDEX idx_agent_experiment_records_request
    ON agent_experiment_records (client_request_id);
