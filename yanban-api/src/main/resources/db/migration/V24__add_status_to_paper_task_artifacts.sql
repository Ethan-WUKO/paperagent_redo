ALTER TABLE paper_task_artifacts
    ADD COLUMN artifact_status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED' AFTER metadata_json;
