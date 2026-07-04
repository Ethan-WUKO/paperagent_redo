CREATE TABLE kb_documents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_kb_documents_user_created (user_id, created_at),
    INDEX idx_kb_documents_public_status (is_public, status),
    CONSTRAINT fk_kb_documents_user FOREIGN KEY (user_id) REFERENCES sys_users (id)
);

CREATE TABLE kb_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    chunk_text LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_kb_chunks_document_index (document_id, chunk_index),
    CONSTRAINT fk_kb_chunks_document FOREIGN KEY (document_id) REFERENCES kb_documents (id) ON DELETE CASCADE
);
