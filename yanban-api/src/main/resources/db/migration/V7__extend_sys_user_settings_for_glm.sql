ALTER TABLE sys_user_settings ADD COLUMN glm_api_key_encrypted TEXT NULL;
ALTER TABLE sys_user_settings ADD COLUMN glm_model VARCHAR(128) NULL;
UPDATE sys_user_settings SET glm_model = 'glm-4.5-air' WHERE glm_model IS NULL;
ALTER TABLE sys_user_settings MODIFY COLUMN glm_model VARCHAR(128) NOT NULL;
