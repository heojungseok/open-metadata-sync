CREATE TABLE work (
    id BINARY(16) NOT NULL,
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
    source_indexed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_work_doi UNIQUE (doi)
) ENGINE=InnoDB;
