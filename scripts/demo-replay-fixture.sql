SET @source_id = UUID_TO_BIN('00000000-0000-0000-0000-00000000d001');
SET @target_id = UUID_TO_BIN('00000000-0000-0000-0000-00000000d002');
SET @zero_hash = UNHEX(REPEAT('00', 32));

INSERT INTO sync_execution (
    id, request_id, mode, sync_contract_hash, canonical_version, business_status,
    expected_count, started_at, created_at, updated_at
) VALUES (
    @source_id, 'demo-replay-source', 'INCREMENTAL', REPEAT('0', 64), 1, 'FAILED',
    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    business_status = 'FAILED', expected_count = 1, finished_at = NULL, updated_at = UTC_TIMESTAMP(6);

INSERT INTO staging_work (
    execution_id, execution_sequence, source_json, doi, title, authors_json,
    canonical_version, content_hash, author_hash, indexed_at, collected_at
) VALUES (
    @source_id, 1, JSON_OBJECT('DOI', '10.5555/demo-replay'), '10.5555/demo-replay',
    'Demo replay', JSON_ARRAY(), 1, @zero_hash, @zero_hash,
    '2026-08-09 00:00:00.000000', UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    source_json = VALUES(source_json), title = VALUES(title), content_hash = VALUES(content_hash),
    author_hash = VALUES(author_hash), indexed_at = VALUES(indexed_at), collected_at = VALUES(collected_at);

SET @staging_key = (
    SELECT staging_key FROM staging_work WHERE execution_id = @source_id AND execution_sequence = 1
);

UPDATE sync_execution
SET staging_upper_bound = @staging_key, updated_at = UTC_TIMESTAMP(6)
WHERE id = @source_id;

INSERT INTO sync_error (
    execution_id, staging_key, error_type, error_code, message, status, replay_count, created_at
) VALUES (
    @source_id, @staging_key, 'VALIDATION', 'DEMO_FIXED', 'Controlled demo error', 'OPEN', 0, UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    message = VALUES(message), status = 'OPEN', replay_count = 0, resolved_at = NULL;

INSERT INTO work (
    id, doi, title, authors_json, canonical_version, content_hash, author_hash,
    source_indexed_at, created_at, updated_at
) VALUES (
    @target_id, '10.5555/demo-replay', 'Demo replay', JSON_ARRAY(), 1, @zero_hash, @zero_hash,
    '2026-08-09 00:00:00.000000', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    title = VALUES(title), authors_json = VALUES(authors_json), canonical_version = VALUES(canonical_version),
    content_hash = VALUES(content_hash), author_hash = VALUES(author_hash),
    source_indexed_at = VALUES(source_indexed_at);

SELECT '00000000-0000-0000-0000-00000000d001' AS source_execution_id;
