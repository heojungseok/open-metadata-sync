CREATE TABLE staging_work (
    staging_key BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    execution_id BINARY(16) NOT NULL,
    execution_sequence BIGINT UNSIGNED NOT NULL,
    source_json JSON NOT NULL,
    doi VARCHAR(255) NOT NULL,
    title TEXT NULL,
    publisher VARCHAR(512) NULL,
    work_type VARCHAR(100) NULL,
    issued_date VARCHAR(10) NULL,
    issued_date_precision TINYINT UNSIGNED NULL,
    url VARCHAR(2048) NULL,
    authors_json JSON NOT NULL,
    canonical_version INT UNSIGNED NOT NULL,
    content_hash BINARY(32) NOT NULL,
    author_hash BINARY(32) NOT NULL,
    indexed_at DATETIME(6) NOT NULL,
    source_created_at DATETIME(6) NULL,
    collected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (staging_key),
    CONSTRAINT uk_staging_execution_sequence UNIQUE (execution_id, execution_sequence),
    CONSTRAINT idx_staging_execution_key UNIQUE (execution_id, staging_key),
    CONSTRAINT fk_staging_execution FOREIGN KEY (execution_id)
        REFERENCES sync_execution (id) ON DELETE RESTRICT,
    INDEX idx_staging_execution_doi_indexed (execution_id, doi, indexed_at)
) ENGINE=InnoDB;

CREATE TABLE sync_error (
    error_key BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    execution_id BINARY(16) NOT NULL,
    staging_key BIGINT UNSIGNED NOT NULL,
    error_type VARCHAR(32) NOT NULL,
    error_code VARCHAR(100) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    replay_count INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (error_key),
    CONSTRAINT uk_sync_error_item UNIQUE (execution_id, staging_key, error_code),
    CONSTRAINT fk_sync_error_staging FOREIGN KEY (execution_id, staging_key)
        REFERENCES staging_work (execution_id, staging_key) ON DELETE RESTRICT,
    INDEX idx_sync_error_replay (status, error_key)
) ENGINE=InnoDB;

CREATE TABLE sync_chunk_result (
    id BINARY(16) NOT NULL,
    execution_id BINARY(16) NOT NULL,
    step_execution_id BIGINT NOT NULL,
    chunk_sequence BIGINT UNSIGNED NOT NULL,
    first_staging_key BIGINT UNSIGNED NOT NULL,
    last_staging_key BIGINT UNSIGNED NOT NULL,
    inserted_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    superseded_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    no_op_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    conflict_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    index_advanced_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    validation_error_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sync_chunk_sequence UNIQUE (execution_id, chunk_sequence),
    CONSTRAINT fk_sync_chunk_first FOREIGN KEY (execution_id, first_staging_key)
        REFERENCES staging_work (execution_id, staging_key) ON DELETE RESTRICT,
    CONSTRAINT fk_sync_chunk_last FOREIGN KEY (execution_id, last_staging_key)
        REFERENCES staging_work (execution_id, staging_key) ON DELETE RESTRICT,
    CONSTRAINT fk_sync_chunk_step FOREIGN KEY (step_execution_id)
        REFERENCES BATCH_STEP_EXECUTION (STEP_EXECUTION_ID) ON DELETE RESTRICT
) ENGINE=InnoDB;
