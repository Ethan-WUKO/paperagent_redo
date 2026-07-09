ALTER TABLE paper_tasks ADD COLUMN score_threshold INT NOT NULL DEFAULT 80;
ALTER TABLE paper_tasks ADD COLUMN max_rounds INT NOT NULL DEFAULT 3;
ALTER TABLE paper_tasks ADD COLUMN inner_max_attempts INT NOT NULL DEFAULT 2;
