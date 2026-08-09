#!/usr/bin/env bash
set -euo pipefail

: "${DEMO_RESET_ACK:?DEMO_RESET_ACK is required}"
: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

if [[ "$DEMO_RESET_ACK" != "INITIAL" ]]; then
  echo "Demo reset requires DEMO_RESET_ACK=INITIAL" >&2
  exit 1
fi
if [[ "$DB_HOST" != "127.0.0.1" && "$DB_HOST" != "localhost" ]]; then
  echo "Demo reset requires a loopback DB host" >&2
  exit 1
fi
if [[ "$DB_PORT" != "3308" ]]; then
  echo "Demo reset requires DB_PORT=3308" >&2
  exit 1
fi

DEMO_DB_CONTAINER="${DEMO_DB_CONTAINER:-open-metadata-sync-demo-mysql}"
if [[ "$DEMO_DB_CONTAINER" != "open-metadata-sync-demo-mysql" ]]; then
  echo "Unexpected demo DB container" >&2
  exit 1
fi

export MYSQL_PWD="$DB_PASSWORD"
expected_tables='BATCH_JOB_EXECUTION,BATCH_JOB_EXECUTION_CONTEXT,BATCH_JOB_EXECUTION_PARAMS,BATCH_JOB_EXECUTION_SEQ,BATCH_JOB_INSTANCE,BATCH_JOB_INSTANCE_SEQ,BATCH_STEP_EXECUTION,BATCH_STEP_EXECUTION_CONTEXT,BATCH_STEP_EXECUTION_SEQ,staging_work,sync_chunk_result,sync_error,sync_execution,sync_watermark,sync_window,work'
actual_tables=$(docker exec -e MYSQL_PWD "$DEMO_DB_CONTAINER" \
  mysql --batch --skip-column-names -u"$DB_USERNAME" information_schema -e \
  "SELECT GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME SEPARATOR ',') FROM TABLES WHERE TABLE_SCHEMA = 'open_metadata_benchmark_preflight' AND TABLE_NAME <> 'flyway_schema_history';")
if [[ "$actual_tables" != "$expected_tables" ]]; then
  echo "Demo reset table contract mismatch" >&2
  exit 1
fi

docker exec -i -e MYSQL_PWD "$DEMO_DB_CONTAINER" \
  mysql -u"$DB_USERNAME" open_metadata_benchmark_preflight <<'SQL'
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
SET FOREIGN_KEY_CHECKS = 1;
SQL

remaining=$(docker exec -e MYSQL_PWD "$DEMO_DB_CONTAINER" \
  mysql --batch --skip-column-names -u"$DB_USERNAME" open_metadata_benchmark_preflight -e \
  "SELECT (SELECT COUNT(*) FROM work) + (SELECT COUNT(*) FROM staging_work) + (SELECT COUNT(*) FROM sync_execution) + (SELECT COUNT(*) FROM BATCH_JOB_INSTANCE);")
if [[ "$remaining" != "0" ]]; then
  echo "Demo reset verification failed" >&2
  exit 1
fi
echo "Demo 10K data reset verified"
