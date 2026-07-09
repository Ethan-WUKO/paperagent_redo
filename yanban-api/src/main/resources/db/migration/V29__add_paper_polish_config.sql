ALTER TABLE paper_tasks
    ADD COLUMN score_threshold INT NOT NULL DEFAULT 80,
    ADD COLUMN max_rounds INT NOT NULL DEFAULT 3,
    ADD COLUMN inner_max_attempts INT NOT NULL DEFAULT 2;
