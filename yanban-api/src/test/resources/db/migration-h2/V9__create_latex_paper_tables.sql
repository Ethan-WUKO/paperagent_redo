ALTER TABLE paper_tasks ADD COLUMN input_format VARCHAR(16) NULL;
ALTER TABLE paper_tasks ADD COLUMN mode VARCHAR(32) NULL;
ALTER TABLE paper_tasks ADD COLUMN main_entry VARCHAR(512) NULL;

CREATE TABLE literature_cards (
    id BIGINT NOT NULL AUTO_INCREMENT,
    doi VARCHAR(255) NULL,
    arxiv_id VARCHAR(128) NULL,
    openalex_id VARCHAR(255) NULL,
    s2_id VARCHAR(255) NULL,
    title_hash VARCHAR(64) NOT NULL,
    title TEXT NOT NULL,
    authors TEXT NULL,
    publication_year INT NULL,
    venue VARCHAR(512) NULL,
    abstract_text LONGTEXT NULL,
    url VARCHAR(1024) NULL,
    pdf_url VARCHAR(1024) NULL,
    citation_count INT NULL,
    referenced_works_json LONGTEXT NULL,
    fields_of_study_json LONGTEXT NULL,
    sources_json LONGTEXT NULL,
    analysis_json LONGTEXT NULL,
    fetched_at TIMESTAMP NULL,
    analyzed_at TIMESTAMP NULL,
    analysis_model_version VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_literature_cards_doi (doi),
    UNIQUE KEY uk_literature_cards_arxiv (arxiv_id),
    UNIQUE KEY uk_literature_cards_openalex (openalex_id),
    UNIQUE KEY uk_literature_cards_s2 (s2_id),
    INDEX idx_literature_cards_title_hash (title_hash),
    INDEX idx_literature_cards_publication_year (publication_year)
);

CREATE TABLE paper_sections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    source_path VARCHAR(512) NULL,
    order_index INT NOT NULL,
    level INT NOT NULL,
    title VARCHAR(512) NOT NULL,
    role VARCHAR(64) NOT NULL,
    role_confidence DOUBLE NULL,
    role_source VARCHAR(32) NULL,
    char_start INT NOT NULL,
    char_end INT NOT NULL,
    original_object_key VARCHAR(512) NULL,
    polished_object_key VARCHAR(512) NULL,
    review_json LONGTEXT NULL,
    diff_json LONGTEXT NULL,
    polish_status VARCHAR(32) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_paper_sections_task FOREIGN KEY (task_id) REFERENCES paper_tasks (id) ON DELETE CASCADE,
    INDEX idx_paper_sections_task_order (task_id, order_index),
    INDEX idx_paper_sections_task_role (task_id, role)
);

CREATE TABLE paper_task_analysis (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    research_profile_json LONGTEXT NULL,
    concept_ladder_json LONGTEXT NULL,
    gap_matrix_json LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_paper_task_analysis_task FOREIGN KEY (task_id) REFERENCES paper_tasks (id) ON DELETE CASCADE,
    CONSTRAINT uk_paper_task_analysis_task UNIQUE (task_id)
);

CREATE TABLE paper_task_artifacts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    type VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    version INT NOT NULL,
    metadata_json LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_paper_task_artifacts_task FOREIGN KEY (task_id) REFERENCES paper_tasks (id) ON DELETE CASCADE,
    CONSTRAINT uk_paper_task_artifacts_task_type_version UNIQUE (task_id, type, version),
    INDEX idx_paper_task_artifacts_task_type (task_id, type)
);

CREATE TABLE paper_task_clarifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    type VARCHAR(64) NOT NULL,
    question_json LONGTEXT NOT NULL,
    options_json LONGTEXT NULL,
    status VARCHAR(32) NOT NULL,
    user_answer_json LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    answered_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_paper_task_clarifications_task FOREIGN KEY (task_id) REFERENCES paper_tasks (id) ON DELETE CASCADE,
    INDEX idx_paper_task_clarifications_task_status (task_id, status)
);

CREATE TABLE paper_task_literature (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    relevance_score DOUBLE NULL,
    narrative_role VARCHAR(32) NULL,
    ladder_node VARCHAR(255) NULL,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    source_query TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_paper_task_literature_task FOREIGN KEY (task_id) REFERENCES paper_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_paper_task_literature_card FOREIGN KEY (card_id) REFERENCES literature_cards (id) ON DELETE CASCADE,
    CONSTRAINT uk_paper_task_literature_task_card UNIQUE (task_id, card_id),
    INDEX idx_paper_task_literature_task_selected (task_id, selected)
);

CREATE TABLE suggestions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    section_id BIGINT NULL,
    track VARCHAR(32) NOT NULL,
    category VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NULL,
    statement TEXT NOT NULL,
    applicable BOOLEAN NOT NULL DEFAULT FALSE,
    patch_json LONGTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PROPOSED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_suggestions_task FOREIGN KEY (task_id) REFERENCES paper_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_suggestions_section FOREIGN KEY (section_id) REFERENCES paper_sections (id) ON DELETE SET NULL,
    INDEX idx_suggestions_task_track (task_id, track),
    INDEX idx_suggestions_task_status (task_id, status)
);

CREATE TABLE suggestion_evidence (
    suggestion_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    PRIMARY KEY (suggestion_id, card_id),
    CONSTRAINT fk_suggestion_evidence_suggestion FOREIGN KEY (suggestion_id) REFERENCES suggestions (id) ON DELETE CASCADE,
    CONSTRAINT fk_suggestion_evidence_card FOREIGN KEY (card_id) REFERENCES literature_cards (id) ON DELETE CASCADE
);
