ALTER TABLE paper_tasks
    ADD COLUMN literature_count INT NULL;

ALTER TABLE literature_cards MODIFY COLUMN abstract_text LONGTEXT NULL;
ALTER TABLE literature_cards MODIFY COLUMN referenced_works_json LONGTEXT NULL;
ALTER TABLE literature_cards MODIFY COLUMN fields_of_study_json LONGTEXT NULL;
ALTER TABLE literature_cards MODIFY COLUMN sources_json LONGTEXT NULL;
ALTER TABLE literature_cards MODIFY COLUMN analysis_json LONGTEXT NULL;
