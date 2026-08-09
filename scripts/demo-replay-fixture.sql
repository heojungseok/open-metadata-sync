SET @source_id = UUID_TO_BIN('00000000-0000-0000-0000-00000000d001');
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
) SELECT
    @source_id, @staging_key, 'PERSISTENCE', 'DEMO_TRANSIENT_WRITE',
    'Simulated transient write failure before target insert', 'OPEN', 0, UTC_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1 FROM sync_error
    WHERE execution_id = @source_id AND error_code = 'DEMO_TRANSIENT_WRITE'
);

SET @demo_error_key = (
    SELECT MIN(error_key) FROM sync_error
    WHERE execution_id = @source_id AND error_code = 'DEMO_TRANSIENT_WRITE'
);

UPDATE sync_error
SET status = 'RESOLVED', resolved_at = COALESCE(resolved_at, UTC_TIMESTAMP(6))
WHERE execution_id = @source_id AND error_key <> @demo_error_key AND status = 'OPEN';

UPDATE sync_error
SET staging_key = @staging_key,
    error_type = 'PERSISTENCE',
    error_code = 'DEMO_TRANSIENT_WRITE',
    message = 'Simulated transient write failure before target insert',
    status = 'OPEN', replay_count = 0, resolved_at = NULL
WHERE error_key = @demo_error_key;

DELETE FROM work WHERE doi = '10.5555/demo-replay';
