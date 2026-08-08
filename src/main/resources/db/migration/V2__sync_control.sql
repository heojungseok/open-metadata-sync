CREATE TABLE sync_execution (
    id BINARY(16) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    sync_contract_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical_version INT UNSIGNED NOT NULL,
    business_status VARCHAR(32) NOT NULL,
    batch_job_execution_id BIGINT NULL,
    source_execution_id BINARY(16) NULL,
    created_from DATE NULL,
    created_until DATE NULL,
    indexed_from_utc DATETIME(6) NULL,
    indexed_until_utc DATETIME(6) NULL,
    max_items BIGINT UNSIGNED NULL,
    expected_count BIGINT UNSIGNED NULL,
    staging_upper_bound BIGINT UNSIGNED NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sync_execution_request UNIQUE (request_id),
    CONSTRAINT uk_sync_execution_batch UNIQUE (batch_job_execution_id),
    CONSTRAINT fk_sync_execution_batch FOREIGN KEY (batch_job_execution_id)
        REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID) ON DELETE RESTRICT,
    CONSTRAINT fk_sync_execution_source FOREIGN KEY (source_execution_id)
        REFERENCES sync_execution (id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE sync_window (
    id BINARY(16) NOT NULL,
    execution_id BINARY(16) NOT NULL,
    window_sequence INT UNSIGNED NOT NULL,
    indexed_from_utc DATETIME(6) NULL,
    indexed_until_utc DATETIME(6) NULL,
    cursor_value TEXT NULL,
    next_cursor_value TEXT NULL,
    collected_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sync_window_sequence UNIQUE (execution_id, window_sequence),
    CONSTRAINT fk_sync_window_execution FOREIGN KEY (execution_id)
        REFERENCES sync_execution (id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE sync_watermark (
    source_name VARCHAR(64) NOT NULL,
    indexed_until_utc DATETIME(6) NOT NULL,
    execution_id BINARY(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_name),
    CONSTRAINT fk_sync_watermark_execution FOREIGN KEY (execution_id)
        REFERENCES sync_execution (id) ON DELETE RESTRICT
) ENGINE=InnoDB;
