ALTER TABLE paper_sections
    ADD COLUMN revision_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';
