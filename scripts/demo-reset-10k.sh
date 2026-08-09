#!/usr/bin/env bash
set -euo pipefail

: "${DEMO_RESET_ACK:?DEMO_RESET_ACK is required}"
: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
source scripts/demo-mysql-client.sh

if [[ "$DEMO_RESET_ACK" != "INITIAL" ]]; then
  echo "Demo reset requires DEMO_RESET_ACK=INITIAL" >&2
  exit 1
fi
demo_validate_database_boundary
demo_verify_database_sentinel open_metadata_benchmark_preflight
expected_tables='BATCH_JOB_EXECUTION,BATCH_JOB_EXECUTION_CONTEXT,BATCH_JOB_EXECUTION_PARAMS,BATCH_JOB_EXECUTION_SEQ,BATCH_JOB_INSTANCE,BATCH_JOB_INSTANCE_SEQ,BATCH_STEP_EXECUTION,BATCH_STEP_EXECUTION_CONTEXT,BATCH_STEP_EXECUTION_SEQ,staging_work,sync_chunk_result,sync_error,sync_execution,sync_watermark,sync_window,work'
actual_tables=$(demo_mysql_query information_schema \
  "SELECT GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME SEPARATOR ',') FROM TABLES WHERE TABLE_SCHEMA = 'open_metadata_benchmark_preflight' AND TABLE_NAME NOT IN ('flyway_schema_history', 'demo_environment_guard');")
if [[ "$actual_tables" != "$expected_tables" ]]; then
  echo "Demo reset table contract mismatch" >&2
  exit 1
fi

demo_mysql_stdin open_metadata_benchmark_preflight <<'SQL'
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

remaining=$(demo_mysql_query open_metadata_benchmark_preflight \
  "SELECT (SELECT COUNT(*) FROM work) + (SELECT COUNT(*) FROM staging_work) + (SELECT COUNT(*) FROM sync_execution) + (SELECT COUNT(*) FROM BATCH_JOB_INSTANCE);")
if [[ "$remaining" != "0" ]]; then
  echo "Demo reset verification failed" >&2
  exit 1
fi
sequence_seed_count=$(demo_mysql_query open_metadata_benchmark_preflight \
  "SELECT (SELECT COUNT(*) FROM BATCH_STEP_EXECUTION_SEQ WHERE ID = 0 AND UNIQUE_KEY = '0') + (SELECT COUNT(*) FROM BATCH_JOB_EXECUTION_SEQ WHERE ID = 0 AND UNIQUE_KEY = '0') + (SELECT COUNT(*) FROM BATCH_JOB_INSTANCE_SEQ WHERE ID = 0 AND UNIQUE_KEY = '0');")
if [[ "$sequence_seed_count" != "3" ]]; then
  echo "Demo reset sequence verification failed" >&2
  exit 1
fi
echo "Demo 10K data reset verified"
