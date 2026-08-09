ALTER TABLE sync_execution
    ADD COLUMN collection_pages_fetched INT UNSIGNED NULL,
    ADD COLUMN collection_reported_total BIGINT UNSIGNED NULL,
    ADD COLUMN collection_stop_reason VARCHAR(32) NULL,
    ADD COLUMN collection_page_safety_cap INT UNSIGNED NULL;
