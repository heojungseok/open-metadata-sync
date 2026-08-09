#!/usr/bin/env bash
set -euo pipefail

: "${DEMO_LIVE_RESET_ACK:?DEMO_LIVE_RESET_ACK is required}"
: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
[[ "$DEMO_LIVE_RESET_ACK" == "LIVE_CROSSREF_10K" ]] || {
  echo "Live reset requires DEMO_LIVE_RESET_ACK=LIVE_CROSSREF_10K" >&2
  exit 1
}
[[ "$DB_USERNAME" == "open_metadata_live_demo" ]] || {
  echo "Live reset requires the live-only database account" >&2
  exit 1
}

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
source scripts/demo-mysql-client.sh
demo_validate_database_boundary
demo_verify_database_sentinel open_metadata_live_demo \
  00000000-0000-0000-0000-00000000d100 \
  'open_metadata_live_demo@%' \
  open-metadata-sync-public-live-demo

expected_tables='BATCH_JOB_EXECUTION,BATCH_JOB_EXECUTION_CONTEXT,BATCH_JOB_EXECUTION_PARAMS,BATCH_JOB_EXECUTION_SEQ,BATCH_JOB_INSTANCE,BATCH_JOB_INSTANCE_SEQ,BATCH_STEP_EXECUTION,BATCH_STEP_EXECUTION_CONTEXT,BATCH_STEP_EXECUTION_SEQ,staging_work,sync_chunk_result,sync_error,sync_execution,sync_watermark,sync_window,work'
actual_tables=$(demo_mysql_query information_schema \
  "SELECT GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME SEPARATOR ',') FROM TABLES WHERE TABLE_SCHEMA = 'open_metadata_live_demo' AND TABLE_NAME NOT IN ('flyway_schema_history', 'demo_environment_guard');")
[[ "$actual_tables" == "$expected_tables" ]] || {
  echo "Live reset table contract mismatch" >&2
  exit 1
}

demo_mysql_stdin open_metadata_live_demo <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE sync_error;
TRUNCATE TABLE sync_chunk_result;
TRUNCATE TABLE staging_work;
TRUNCATE TABLE sync_watermark;
TRUNCATE TABLE sync_window;
TRUNCATE TABLE sync_execution;
TRUNCATE TABLE work;
TRUNCATE TABLE BATCH_STEP_EXECUTION_CONTEXT;
TRUNCATE TABLE BATCH_STEP_EXECUTION;
TRUNCATE TABLE BATCH_JOB_EXECUTION_CONTEXT;
TRUNCATE TABLE BATCH_JOB_EXECUTION_PARAMS;
TRUNCATE TABLE BATCH_JOB_EXECUTION;
TRUNCATE TABLE BATCH_JOB_INSTANCE;
TRUNCATE TABLE BATCH_STEP_EXECUTION_SEQ;
TRUNCATE TABLE BATCH_JOB_EXECUTION_SEQ;
TRUNCATE TABLE BATCH_JOB_INSTANCE_SEQ;
INSERT INTO BATCH_STEP_EXECUTION_SEQ (ID, UNIQUE_KEY) VALUES (0, '0');
INSERT INTO BATCH_JOB_EXECUTION_SEQ (ID, UNIQUE_KEY) VALUES (0, '0');
INSERT INTO BATCH_JOB_INSTANCE_SEQ (ID, UNIQUE_KEY) VALUES (0, '0');
SET FOREIGN_KEY_CHECKS = 1;
SQL

remaining=$(demo_mysql_query open_metadata_live_demo \
  "SELECT (SELECT COUNT(*) FROM work) + (SELECT COUNT(*) FROM staging_work) + (SELECT COUNT(*) FROM sync_execution) + (SELECT COUNT(*) FROM BATCH_JOB_INSTANCE);")
[[ "$remaining" == "0" ]] || { echo "Live reset verification failed" >&2; exit 1; }
echo "Live Crossref data reset verified"
