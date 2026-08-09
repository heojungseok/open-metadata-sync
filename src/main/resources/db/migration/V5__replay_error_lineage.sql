ALTER TABLE staging_work
    ADD COLUMN source_error_key BIGINT UNSIGNED NULL,
    ADD CONSTRAINT uk_staging_replay_source_error UNIQUE (execution_id, source_error_key),
    ADD CONSTRAINT fk_staging_source_error FOREIGN KEY (source_error_key)
        REFERENCES sync_error (error_key) ON DELETE RESTRICT;
