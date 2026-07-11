ALTER TABLE kb_documents ADD COLUMN object_key VARCHAR(512) NULL;
ALTER TABLE kb_documents ADD COLUMN mime_type VARCHAR(255) NULL;
ALTER TABLE kb_documents ADD COLUMN file_size BIGINT NULL;
ALTER TABLE kb_documents ADD COLUMN error_message TEXT NULL;

ALTER TABLE kb_chunks ADD COLUMN es_doc_id VARCHAR(255) NULL;

CREATE TABLE kb_chunk_uploads (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    upload_id VARCHAR(64) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    chunk_number INT NOT NULL,
    total_chunks INT NOT NULL,
    chunk_size BIGINT NOT NULL,
    chunk_md5 VARCHAR(64) NULL,
    temp_object_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_kb_chunk_uploads_upload_chunk UNIQUE (upload_id, chunk_number),
    INDEX idx_kb_chunk_uploads_user_created (user_id, created_at)
);

CREATE TABLE paper_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    source_filename VARCHAR(255) NULL,
    object_key VARCHAR(512) NULL,
    status VARCHAR(32) NOT NULL,
    target_language VARCHAR(16) NOT NULL,
    current_stage VARCHAR(64) NULL,
    error_message TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_paper_tasks_user_created (user_id, created_at)
);

CREATE TABLE paper_task_rounds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    round_number INT NOT NULL,
    stage VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_text LONGTEXT NULL,
    output_text LONGTEXT NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_paper_task_rounds_task FOREIGN KEY (task_id) REFERENCES paper_tasks (id) ON DELETE CASCADE,
    CONSTRAINT uk_paper_task_rounds_task_round UNIQUE (task_id, round_number, stage),
    INDEX idx_paper_task_rounds_task_created (task_id, created_at)
);
