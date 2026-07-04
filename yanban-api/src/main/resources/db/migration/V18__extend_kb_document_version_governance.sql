ALTER TABLE kb_documents ADD COLUMN project_id BIGINT NULL;
ALTER TABLE kb_documents ADD COLUMN lineage_id VARCHAR(64) NULL;
ALTER TABLE kb_documents ADD COLUMN version_no INT NOT NULL DEFAULT 1;
ALTER TABLE kb_documents ADD COLUMN version_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE kb_documents ADD COLUMN source_task_type VARCHAR(64) NULL;
ALTER TABLE kb_documents ADD COLUMN source_task_id BIGINT NULL;
ALTER TABLE kb_documents ADD COLUMN source_artifact_id BIGINT NULL;
ALTER TABLE kb_documents ADD COLUMN source_document_id BIGINT NULL;
ALTER TABLE kb_documents ADD COLUMN canonical_key VARCHAR(128) NULL;
ALTER TABLE kb_documents ADD COLUMN effective_at TIMESTAMP NULL;
ALTER TABLE kb_documents ADD COLUMN superseded_at TIMESTAMP NULL;
ALTER TABLE kb_documents ADD COLUMN deleted_at TIMESTAMP NULL;

UPDATE kb_documents SET effective_at = created_at WHERE effective_at IS NULL;

CREATE INDEX idx_kb_documents_user_status_updated ON kb_documents (user_id, version_status, updated_at);
CREATE INDEX idx_kb_documents_lineage_version ON kb_documents (lineage_id, version_no);
CREATE INDEX idx_kb_documents_project_status ON kb_documents (project_id, version_status);
CREATE INDEX idx_kb_documents_source_task ON kb_documents (source_task_type, source_task_id);
CREATE INDEX idx_kb_documents_canonical_key ON kb_documents (user_id, canonical_key);
